package ai.cashdrive.app.network

/** Immutable wire-facing records. Status strings preserve the server's exact state values. */
data class SandboxIdentity(val accountAlias: String, val installationId: String)

data class SandboxLogin(
    val accountId: String,
    val installationId: String,
    val accessToken: String,
    val mode: String,
) {
    override fun toString(): String = "SandboxLogin([redacted])"
}

data class TripBaseline(
    val installationId: String,
    val tripId: String,
    val bootId: String,
    val odometerMeters: Long,
    val elapsedMs: Long,
)

data class TripAccepted(
    val accepted: Boolean,
    val tripId: String,
    val bootId: String,
    val baselineStatus: String,
)

data class DistanceChunk(
    val installationId: String,
    val tripId: String,
    val sequence: Long,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val distanceMeters: Long,
    val odometerStartMeters: Long,
    val odometerEndMeters: Long,
)

data class RewardTicket(
    val id: String,
    val points: Int,
    val status: String,
    val createdAtEpochSeconds: Long? = null,
)

data class DistanceAccepted(
    val accepted: Boolean,
    val distanceMeters: Long,
    val fractionalMeters: Long,
    val tickets: List<RewardTicket>,
    val evidenceStatus: String,
)

data class AdSession(
    val id: String,
    val ticketId: String,
    val provider: String,
    val expiresAtEpochSeconds: Long,
    val status: String,
)

data class AdStart(val adSessionId: String, val startedAtEpochSeconds: Long, val status: String)

data class SandboxCompletion(
    val accepted: Boolean,
    val duplicate: Boolean,
    val proofId: String?,
    val providerTransactionId: String?,
    val source: String,
    val ledgerEntry: LedgerEntry?,
)

data class LedgerEntry(val id: String, val points: Int, val status: String)

data class ClaimResult(val status: String, val ledgerEntry: LedgerEntry?)

data class Wallet(val accountId: String, val confirmedPoints: Long)
