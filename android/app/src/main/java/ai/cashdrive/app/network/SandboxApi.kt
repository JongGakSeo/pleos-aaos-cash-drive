package ai.cashdrive.app.network

import android.os.Looper
import ai.cashdrive.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/** Structured server response error. The bearer token and request body are never included. */
class SandboxApiException(
    val httpStatus: Int,
    val errorCode: String,
    val serverDetail: String? = null,
) : IOException("Cash Drive API error: $errorCode ($httpStatus)")

/**
 * Blocking HTTP client for the local backend. Call only from a worker executor.
 * It deliberately supports only the loopback emulator bridge and fixed sandbox port.
 */
class SandboxApi(
    baseUrl: String,
    sandboxEnabled: Boolean,
) {
    private val rootUrl: String

    init {
        require(BuildConfig.DEBUG && sandboxEnabled) { "Sandbox API is available only in an explicitly enabled debug build" }
        val uri = try { URI(baseUrl) } catch (e: Exception) { throw IllegalArgumentException("Invalid sandbox base URL") }
        val allowedHosts = setOf("10.0.2.2", "127.0.0.1", "localhost")
        require(uri.scheme == "http" && uri.host in allowedHosts && uri.port == 8787) {
            "Sandbox API must use the loopback bridge at http://<allowed-loopback-host>:8787"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
            "Sandbox base URL cannot contain credentials, a path, query, or fragment"
        }
        rootUrl = "http://${uri.rawAuthority}"
    }

    fun loginSandbox(identity: SandboxIdentity): SandboxLogin {
        requireIdentifier(identity.accountAlias, 80, "accountAlias")
        requireIdentifier(identity.installationId, 100, "installationId")
        val body = jsonObject("accountAlias" to identity.accountAlias, "installationId" to identity.installationId)
        return request("POST", "/v1/sandbox/login", body).let {
            SandboxLogin(it.string("accountId"), it.string("installationId"), it.string("accessToken"), it.string("mode"))
        }
    }

    fun startTrip(token: String, baseline: TripBaseline, idempotencyKey: String): TripAccepted {
        requireIdentifier(baseline.installationId, 100, "installationId")
        requireIdentifier(baseline.tripId, 100, "tripId")
        requireIdentifier(baseline.bootId, 100, "bootId")
        requireNonNegative(baseline.odometerMeters, "odometerMeters")
        requireNonNegative(baseline.elapsedMs, "elapsedMs")
        val body = jsonObject("schemaVersion" to 1, "installationId" to baseline.installationId,
            "tripId" to baseline.tripId, "bootId" to baseline.bootId,
            "odometerMeters" to baseline.odometerMeters, "elapsedMs" to baseline.elapsedMs)
        return request("POST", "/v1/trips", body, token, idempotencyKey).let {
            TripAccepted(it.boolean("accepted"), it.string("tripId"), it.string("bootId"), it.string("baselineStatus"))
        }
    }

    fun submitDistanceChunk(token: String, chunk: DistanceChunk, idempotencyKey: String): DistanceAccepted {
        requireIdentifier(chunk.installationId, 100, "installationId")
        requireIdentifier(chunk.tripId, 100, "tripId")
        requireNonNegative(chunk.sequence, "sequence")
        requireNonNegative(chunk.startElapsedMs, "startElapsedMs")
        requireNonNegative(chunk.endElapsedMs, "endElapsedMs")
        requireNonNegative(chunk.distanceMeters, "distanceMeters")
        requireNonNegative(chunk.odometerStartMeters, "odometerStartMeters")
        requireNonNegative(chunk.odometerEndMeters, "odometerEndMeters")
        val body = jsonObject("schemaVersion" to 1, "installationId" to chunk.installationId,
            "tripId" to chunk.tripId, "sequence" to chunk.sequence,
            "startElapsedMs" to chunk.startElapsedMs, "endElapsedMs" to chunk.endElapsedMs,
            "distanceMeters" to chunk.distanceMeters, "odometerStartMeters" to chunk.odometerStartMeters,
            "odometerEndMeters" to chunk.odometerEndMeters)
        return request("POST", "/v1/distance-chunks", body, token, idempotencyKey).let { result ->
            val tickets = result.array("tickets").map { parseTicket(it as? JSONObject ?: throw IOException("Invalid ticket response")) }
            DistanceAccepted(result.boolean("accepted"), result.long("distanceMeters"), result.long("fractionalMeters"),
                tickets.toList(), result.string("evidenceStatus"))
        }
    }

    fun wallet(token: String): Wallet = request("GET", "/v1/wallet", token = token).let {
        Wallet(it.string("accountId"), it.long("confirmedPoints"))
    }

    fun tickets(token: String): List<RewardTicket> = request("GET", "/v1/tickets", token = token)
        .array("tickets").map { parseTicket(it as? JSONObject ?: throw IOException("Invalid ticket response")) }.toList()

    fun createAdSession(token: String, rewardTicketId: String, idempotencyKey: String): AdSession {
        requireIdentifier(rewardTicketId, 100, "rewardTicketId")
        return request("POST", "/v1/ad-sessions", jsonObject("schemaVersion" to 1,
            "rewardTicketId" to rewardTicketId, "placement" to "PARKED_REWARDED"), token, idempotencyKey).let(::parseAdSession)
    }

    fun startAdSession(token: String, adSessionId: String, idempotencyKey: String): AdStart {
        requireIdentifier(adSessionId, 100, "adSessionId")
        return request("POST", "/v1/ad-sessions/start", jsonObject("schemaVersion" to 1,
            "adSessionId" to adSessionId), token, idempotencyKey).let {
            AdStart(it.string("adSessionId"), it.long("startedAt"), it.string("status"))
        }
    }

    /** Generates a local NON_PROVIDER_SANDBOX proof on the server; no webhook secret is sent to Android. */
    fun completeSandboxAd(token: String, adSessionId: String, idempotencyKey: String): SandboxCompletion {
        requireIdentifier(adSessionId, 100, "adSessionId")
        return request("POST", "/v1/sandbox/ad-complete", jsonObject("schemaVersion" to 1,
            "adSessionId" to adSessionId), token, idempotencyKey).let {
            SandboxCompletion(it.boolean("accepted"), it.optBoolean("duplicate", false),
                it.optNullableString("proofId"), it.optNullableString("providerTransactionId"), it.string("source"),
                it.optObject("ledgerEntry")?.let(::parseLedgerEntry))
        }
    }

    fun claimReward(token: String, rewardTicketId: String, adSessionId: String, idempotencyKey: String): ClaimResult {
        requireIdentifier(rewardTicketId, 100, "rewardTicketId")
        requireIdentifier(adSessionId, 100, "adSessionId")
        return request("POST", "/v1/reward-claims", jsonObject("schemaVersion" to 1,
            "rewardTicketId" to rewardTicketId, "adSessionId" to adSessionId, "userConfirmed" to true), token, idempotencyKey).let {
            ClaimResult(it.string("status"), it.optObject("ledgerEntry")?.let(::parseLedgerEntry))
        }
    }

    fun logout(token: String) {
        request("POST", "/v1/logout", JSONObject(), token)
    }

    /** Revoke remotely, then clear local credentials even when the network is unavailable. */
    fun logout(token: String, tokenVault: TokenVault) {
        try {
            logout(token)
        } finally {
            tokenVault.clear()
        }
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        idempotencyKey: String? = null,
    ): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "SandboxApi is blocking; call it from a worker executor" }
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        if (token != null) require(token.length in 20..256 && token.none { it.isWhitespace() }) { "Invalid bearer token" }
        if (idempotencyKey != null) require(IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) { "Invalid Idempotency-Key" }
        val connection = (URI(rootUrl + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (idempotencyKey != null) setRequestProperty("Idempotency-Key", idempotencyKey)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
                if (payload.size > MAX_BODY_BYTES) throw IllegalArgumentException("Request body too large")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use { readBounded(it) } ?: ByteArray(0)
            val responseText = String(responseBytes, StandardCharsets.UTF_8)
            val response = try { JSONObject(responseText) } catch (_: Exception) {
                if (status !in 200..299) throw SandboxApiException(status, "invalid_error_response")
                throw IOException("Invalid JSON response")
            }
            if (status !in 200..299) {
                throw SandboxApiException(status, response.optString("error", "http_error"), response.optString("detail").takeIf { it.isNotEmpty() })
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BODY_BYTES) throw IOException("Response body too large")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun parseTicket(value: JSONObject) = RewardTicket(
        value.string("id"), value.int("points"), value.string("status"), value.optLongOrNull("created_at"),
    )

    private fun parseAdSession(value: JSONObject) = AdSession(
        value.string("id"), value.string("ticketId"), value.string("provider"), value.long("expiresAt"), value.string("status"),
    )

    private fun parseLedgerEntry(value: JSONObject) = LedgerEntry(value.string("id"), value.int("points"), value.string("status"))

    private fun requireIdentifier(value: String, max: Int, name: String) {
        require(value.isNotEmpty() && value.length <= max && '\u0000' !in value) { "Invalid $name" }
    }

    private fun requireNonNegative(value: Long, name: String) {
        require(value >= 0) { "Invalid $name" }
    }

    private fun jsonObject(vararg fields: Pair<String, Any>): JSONObject = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value) }
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String
        ?: throw IOException("Missing or invalid response field: $key")

    private fun JSONObject.long(key: String): Long = opt(key).asLongValue(key)
    private fun JSONObject.int(key: String): Int = opt(key).asLongValue(key).also {
        if (it < Int.MIN_VALUE.toLong() || it > Int.MAX_VALUE.toLong()) throw IOException("Invalid response field: $key")
    }.toInt()
    private fun JSONObject.boolean(key: String): Boolean = opt(key) as? Boolean
        ?: throw IOException("Missing or invalid response field: $key")
    private fun JSONObject.array(key: String): List<Any> {
        val array = opt(key) as? JSONArray ?: throw IOException("Missing or invalid response field: $key")
        return (0 until array.length()).map { array.get(it) }
    }
    private fun JSONObject.optObject(key: String): JSONObject? = when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        is JSONObject -> value
        else -> throw IOException("Invalid response field: $key")
    }
    private fun JSONObject.optNullableString(key: String): String? = when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        is String -> value
        else -> throw IOException("Invalid response field: $key")
    }
    private fun JSONObject.optLongOrNull(key: String): Long? = when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        else -> value.asLongValue(key)
    }

    private fun Any?.asLongValue(name: String): Long {
        val number = this as? Number ?: throw IOException("Missing or invalid response field: $name")
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Long.MIN_VALUE.toDouble() || double > Long.MAX_VALUE.toDouble()) {
            throw IOException("Invalid response field: $name")
        }
        return number.toLong()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_BODY_BYTES = 1_048_576
        val IDEMPOTENCY_KEY: Pattern = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$")
    }
}
