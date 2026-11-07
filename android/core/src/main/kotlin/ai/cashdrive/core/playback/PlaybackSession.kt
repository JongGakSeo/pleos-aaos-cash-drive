package ai.cashdrive.core.playback

import ai.cashdrive.core.session.ThemeIds

/** UI contract v2. Player and audio focus belong to the UI; this domain never runs a timer. */
interface PlaybackSession {
    val state: PlaybackState
    /** Immediate/current and subsequent changes, on the calling thread. Close on disposal. */
    fun observe(listener: (PlaybackState) -> Unit): AutoCloseable
    /** Save the actual player's position, including its final position after permission is revoked. */
    fun onPlaybackPosition(adId: String, positionMs: Long): PlaybackResult
    /** LOCAL_MOCK completion only. Atomically credit 10 P once and select the next ad. */
    fun onAdCompleted(adId: String): PlaybackResult
    fun selectTheme(themeId: String): PlaybackResult
}

enum class VehicleState { DRIVING, STOPPED_PARKED, STOPPED_WAITING, UNKNOWN }

/** No persisted safety authorization: a new host must collect fresh authoritative signals. */
data class VehicleStatus(
    val vehicleState: VehicleState = VehicleState.UNKNOWN,
    val speedKph: Double? = null,
    val connected: Boolean = false,
    val speedPermissionGranted: Boolean = false,
    val activeRestrictions: Int? = null,
    val requiresDistractionOptimization: Boolean? = null,
) {
    val stateConfirmed: Boolean get() = connected && speedPermissionGranted &&
        speedKph != null && speedKph.isFinite() && speedKph >= 0 &&
        vehicleState != VehicleState.UNKNOWN &&
        (vehicleState == VehicleState.DRIVING || speedKph == 0.0)
    // AAOS CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO = 0x10. Never clear a received bit.
    val playbackAllowed: Boolean get() = stateConfirmed && speedKph == 0.0 &&
        vehicleState in setOf(VehicleState.STOPPED_PARKED, VehicleState.STOPPED_WAITING) &&
        requiresDistractionOptimization != null && activeRestrictions != null &&
        activeRestrictions >= 0 && activeRestrictions and NO_VIDEO == 0
    // The new waiting-stop exception is for playback, not setup/theme/account actions.
    val parkedActionsAllowed: Boolean get() = stateConfirmed &&
        vehicleState == VehicleState.STOPPED_PARKED && activeRestrictions == 0 &&
        requiresDistractionOptimization == false

    companion object { const val NO_VIDEO = 0x10 }
}

data class AdPlayback(val adId: String, val positionMs: Long, val durationMs: Long)
data class PlaybackState(
    val accountId: String,
    val vehicle: VehicleStatus,
    val totalPoints: Int,
    val tripCandidatePoints: Int,
    /** Includes the current, partially played ad. Credited ads are excluded. */
    val remainingAdCount: Int,
    val currentAd: AdPlayback?,
    val themeId: String,
    val storageAvailable: Boolean,
    val mode: String = "LOCAL_MOCK",
) {
    val vehicleState: VehicleState get() = if (vehicle.stateConfirmed) vehicle.vehicleState else VehicleState.UNKNOWN
    val speedKph: Double? get() = vehicle.speedKph?.takeIf { it.isFinite() && it >= 0 }
    /** Permission to play; the UI also needs a current ad and a foreground player. */
    val playbackAllowed: Boolean get() = storageAvailable && vehicle.playbackAllowed
}

enum class PlaybackCode { OK, LOCKED, UNKNOWN_AD, INVALID_EVENT, STORAGE_ERROR, CLOSED }
data class PlaybackResult(val code: PlaybackCode, val state: PlaybackState)

enum class CreditReason { MOCK_AD_AUTO_COMPLETION, IMPORTED_V1_CREDIT }
data class MockCredit(val adId: String, val points: Int = 10,
    val reason: CreditReason = CreditReason.MOCK_AD_AUTO_COMPLETION)
data class PlaybackAccountRecord(
    val accountId: String,
    val ads: List<AdPlayback> = emptyList(),
    val ledger: List<MockCredit> = emptyList(),
    val openingPoints: Int = 0,
    val tripCandidatePoints: Int = 0,
    val tripId: String = "mock-initial",
    val themeId: String? = ThemeIds.DEFAULT,
) {
    val totalPoints: Int get() = ledger.fold(openingPoints) { sum, entry -> Math.addExact(sum, entry.points) }
    fun validate(): PlaybackAccountRecord {
        require(accountId.isNotBlank() && openingPoints >= 0 && tripCandidatePoints >= 0 && tripId.isNotBlank())
        require(ads.all { it.adId.isNotBlank() && it.durationMs > 0 && it.positionMs in 0..it.durationMs })
        require(ads.map { it.adId }.distinct().size == ads.size)
        require(ledger.map { it.adId }.distinct().size == ledger.size)
        require(ledger.all { entry -> ads.any { it.adId == entry.adId } &&
            entry.points == if (entry.reason == CreditReason.IMPORTED_V1_CREDIT) 0 else 10 })
        require(totalPoints >= 0)
        return this
    }
}

/** Atomic per-account read/transform/durable commit; serialize across host/store instances. */
interface PlaybackAccountStore {
    fun read(accountId: String): PlaybackAccountRecord?
    fun update(accountId: String, change: (PlaybackAccountRecord?) -> PlaybackAccountRecord): PlaybackAccountRecord
}
