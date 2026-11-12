package ai.cashdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.app.network.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Local server must already be running. Synthetic API exercise, never an ad-provider proof. */
@RunWith(AndroidJUnit4::class)
class SandboxIntegrationTest {
    @Test fun syntheticRoundTripIsIdempotentAndCredentialsAreRevoked() {
        val api = SandboxApi("http://10.0.2.2:8787", sandboxEnabled = true)
        val vault = TokenVault(InstrumentationRegistry.getInstrumentation().targetContext)
        val previousSession = vault.load()
        val id = UUID.randomUUID().toString()
        val login = api.loginSandbox(SandboxIdentity("instrumentation-$id", "install-$id"))
        try {
            vault.save(login)
            assertEquals(login, vault.load())
            val baseline = TripBaseline(login.installationId, "trip-$id", "boot-$id", 1000, 0)
            assertTrue(api.startTrip(login.accessToken, baseline, "baseline-$id").accepted)
            val chunk = DistanceChunk(login.installationId, baseline.tripId, 0, 0, 60_000, 1000, 1000, 2000)
            val first = api.submitDistanceChunk(login.accessToken, chunk, "distance-$id")
            val replay = api.submitDistanceChunk(login.accessToken, chunk, "distance-$id")
            assertEquals(first, replay)
            assertEquals("CLIENT_REPORTED_UNVERIFIED", first.evidenceStatus)
            assertEquals(1, api.tickets(login.accessToken).size)
            assertEquals(0L, api.wallet(login.accessToken).confirmedPoints)
            val ticket = first.tickets.single()
            val ad = api.createAdSession(login.accessToken, ticket.id, "session-$id")
            api.startAdSession(login.accessToken, ad.id, "start-$id")
            // This wait exercises only the server's synthetic minimum-time rule; no actual ad is shown.
            Thread.sleep(6_000)
            val completion = api.completeSandboxAd(login.accessToken, ad.id, "complete-$id")
            assertTrue(completion.accepted)
            assertEquals("NON_PROVIDER_SANDBOX", completion.source)
            assertEquals(0L, api.wallet(login.accessToken).confirmedPoints)
            // Simulate a lost response body after the server commits: deliberately do not
            // consume its JSON. Recover using the original persisted operation key.
            val request = (URL("http://10.0.2.2:8787/v1/reward-claims").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("Authorization", "Bearer ${login.accessToken}")
                setRequestProperty("Idempotency-Key", "claim-$id")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val body = JSONObject().put("schemaVersion", 1).put("rewardTicketId", ticket.id)
                    .put("adSessionId", ad.id).put("userConfirmed", true).toString().toByteArray()
                request.setFixedLengthStreamingMode(body.size)
                request.outputStream.use { it.write(body) }
                assertEquals(200, request.responseCode)
            } finally { request.disconnect() }
            val claim = api.claimReward(login.accessToken, ticket.id, ad.id, "claim-$id")
            assertEquals("CREDITED", claim.status)
            assertEquals(claim, api.claimReward(login.accessToken, ticket.id, ad.id, "claim-$id"))
            try {
                api.claimReward(login.accessToken, ticket.id, ad.id, "duplicate-$id")
                fail("A different operation key must not start a second claim")
            } catch (error: SandboxApiException) {
                assertEquals(409, error.httpStatus)
                assertEquals("claim_already_started", error.errorCode)
            }
            assertEquals(10L, api.wallet(login.accessToken).confirmedPoints)
            api.logout(login.accessToken, vault)
            assertNull(vault.load())
            try {
                api.wallet(login.accessToken)
                fail("Revoked token must not read the wallet")
            } catch (error: SandboxApiException) {
                assertEquals(401, error.httpStatus)
            }
        } finally {
            runCatching { api.logout(login.accessToken) }
            if (previousSession == null) vault.clear() else vault.save(previousSession)
        }
    }
}
