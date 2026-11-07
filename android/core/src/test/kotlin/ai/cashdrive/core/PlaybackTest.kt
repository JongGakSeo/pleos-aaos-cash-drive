package ai.cashdrive.core

import ai.cashdrive.core.playback.*
import org.junit.Test
import kotlin.test.*

class PlaybackTest {
    private val parked = VehicleStatus(VehicleState.STOPPED_PARKED, 0.0, true, true, 0, false)
    private val waiting = parked.copy(vehicleState = VehicleState.STOPPED_WAITING,
        requiresDistractionOptimization = true, activeRestrictions = 64)
    private class Store : PlaybackAccountStore {
        val records = mutableMapOf<String, PlaybackAccountRecord>()
        var failBefore = false
        var loseResponse = false
        @Synchronized override fun read(accountId: String) = records[accountId]
        @Synchronized override fun update(accountId: String, change: (PlaybackAccountRecord?) -> PlaybackAccountRecord): PlaybackAccountRecord {
            if (failBefore) error("disk failure")
            val next = change(records[accountId]).validate()
            records[accountId] = next
            if (loseResponse) { loseResponse = false; error("durable commit, response lost") }
            return next
        }
    }
    private fun session(store: Store = Store(), account: String = "A") = MockPlaybackSession(account, store)

    @Test fun bothConfirmedStopsAllowPlaybackWithoutBroadRequiresDoGate() {
        assertTrue(parked.playbackAllowed); assertTrue(waiting.playbackAllowed)
        assertFalse(waiting.parkedActionsAllowed)
    }
    @Test fun noVideoAlwaysWinsEvenIfRequiresDoFalse() {
        listOf(true, false).forEach { doRequired ->
            assertFalse(parked.copy(requiresDistractionOptimization = doRequired, activeRestrictions = 16).playbackAllowed)
        }
    }
    @Test fun missingSignalsPermissionOrConnectionNeverAllowPlayback() {
        listOf(VehicleStatus(), parked.copy(connected = false), parked.copy(speedPermissionGranted = false),
            parked.copy(speedKph = null), parked.copy(activeRestrictions = null),
            parked.copy(requiresDistractionOptimization = null), parked.copy(vehicleState = VehicleState.UNKNOWN),
            parked.copy(activeRestrictions = -1)).forEach { assertFalse(it.playbackAllowed, it.toString()) }
    }
    @Test fun zeroSpeedAloneOrContradictoryMovingStopNeverAuthorizes() {
        listOf(parked.copy(vehicleState = VehicleState.UNKNOWN), parked.copy(vehicleState = VehicleState.DRIVING),
            parked.copy(speedKph = 0.001), parked.copy(speedKph = Double.NaN),
            parked.copy(speedKph = Double.POSITIVE_INFINITY), parked.copy(speedKph = -1.0))
            .forEach { assertFalse(it.playbackAllowed) }
    }
    @Test fun startsUnknownAndAutomaticallySelectsFirstAd() {
        val s = session(); assertEquals(VehicleState.UNKNOWN, s.state.vehicleState)
        assertFalse(s.state.playbackAllowed); assertEquals("mock-initial-1", s.state.currentAd?.adId)
        s.updateVehicle(waiting); assertTrue(s.state.playbackAllowed)
        assertEquals(3, s.state.remainingAdCount)
    }
    @Test fun completionAutomaticallyCreditsTenAndAdvancesWithoutConfirmation() {
        val s = session(); s.updateVehicle(waiting)
        assertEquals(PlaybackCode.OK, s.onAdCompleted("mock-initial-1").code)
        assertEquals(10, s.state.totalPoints); assertEquals(2, s.state.remainingAdCount)
        assertEquals("mock-initial-2", s.state.currentAd?.adId)
        assertEquals(30, s.state.tripCandidatePoints)
    }
    @Test fun departureDeniesCompletionButSavesFinalPositionAndResumes() {
        val s = session(); s.updateVehicle(parked); s.onPlaybackPosition("mock-initial-1", 4200)
        s.updateVehicle(parked.copy(vehicleState = VehicleState.DRIVING, speedKph = 30.0))
        assertFalse(s.state.playbackAllowed)
        assertEquals(PlaybackCode.LOCKED, s.onAdCompleted("mock-initial-1").code)
        assertEquals(PlaybackCode.OK, s.onPlaybackPosition("mock-initial-1", 4250).code)
        s.updateVehicle(waiting); assertTrue(s.state.playbackAllowed)
        assertEquals(4250, s.state.currentAd?.positionMs)
    }
    @Test fun restartPreservesPositionAndRemainingButDoesNotRestoreSafety() {
        val db = Store(); session(db).apply { updateVehicle(parked); onPlaybackPosition("mock-initial-1", 3210); close() }
        val restored = session(db)
        assertEquals(3210, restored.state.currentAd?.positionMs); assertEquals(3, restored.state.remainingAdCount)
        assertFalse(restored.state.playbackAllowed)
    }
    @Test fun accountSwitchSeparatesThemePositionAndLedger() {
        val db = Store(); val a = session(db); a.updateVehicle(parked)
        a.selectTheme("mining"); a.onAdCompleted("mock-initial-1"); a.onPlaybackPosition("mock-initial-2", 2500); a.close()
        val b = session(db, "B"); b.updateVehicle(parked); b.selectTheme("mine")
        assertEquals(0, b.state.totalPoints); assertEquals(0, b.state.currentAd?.positionMs)
        val restored = session(db)
        assertEquals("mining", restored.state.themeId); assertEquals(10, restored.state.totalPoints)
        assertEquals(2500, restored.state.currentAd?.positionMs); assertEquals(2, restored.state.remainingAdCount)
    }
    @Test fun duplicateCompletionAcrossSessionsAndRestartCreditsOnce() {
        val db = Store(); val a = session(db); val other = session(db)
        a.updateVehicle(parked); other.updateVehicle(waiting)
        a.onAdCompleted("mock-initial-1"); other.onAdCompleted("mock-initial-1")
        repeat(4) { a.onAdCompleted("mock-initial-1") }
        assertEquals(10, session(db).state.totalPoints); assertEquals(1, db.read("A")!!.ledger.size)
    }
    @Test fun responseLossAfterCommitThenRetryCannotDoubleCredit() {
        val db = Store(); val a = session(db); a.updateVehicle(parked); db.loseResponse = true
        assertEquals(PlaybackCode.STORAGE_ERROR, a.onAdCompleted("mock-initial-1").code)
        assertFalse(a.state.playbackAllowed)
        val reloaded = session(db); reloaded.updateVehicle(waiting)
        assertEquals(PlaybackCode.OK, reloaded.onAdCompleted("mock-initial-1").code)
        assertEquals(10, reloaded.state.totalPoints); assertEquals(2, reloaded.state.remainingAdCount)
    }
    @Test fun failedCommitNeverCreditsOrPermitsFurtherPlayback() {
        val db = Store(); val s = session(db); s.updateVehicle(parked); db.failBefore = true
        assertEquals(PlaybackCode.STORAGE_ERROR, s.onAdCompleted("mock-initial-1").code)
        assertEquals(0, s.state.totalPoints); assertFalse(s.state.playbackAllowed)
        db.failBefore = false; assertEquals(0, session(db).state.totalPoints)
    }
    @Test fun outOfOrderProgressNeverRewindsAndFutureAdCannotComplete() {
        val s = session(); s.updateVehicle(parked)
        s.onPlaybackPosition("mock-initial-1", 3000); s.onPlaybackPosition("mock-initial-1", 1000)
        assertEquals(3000, s.state.currentAd?.positionMs)
        assertEquals(PlaybackCode.INVALID_EVENT, s.onAdCompleted("mock-initial-2").code)
        assertEquals(PlaybackCode.INVALID_EVENT, s.onPlaybackPosition("mock-initial-1", 15001).code)
        assertEquals(PlaybackCode.UNKNOWN_AD, s.onAdCompleted("missing").code)
    }
    @Test fun exhaustedQueueStaysEmptyAfterRestart() {
        val db = Store(); val s = session(db); s.updateVehicle(waiting)
        (1..3).forEach { s.onAdCompleted("mock-initial-$it") }
        val restored = session(db); assertEquals(30, restored.state.totalPoints)
        assertNull(restored.state.currentAd); assertEquals(0, restored.state.remainingAdCount)
    }
    @Test fun invalidThemeDefaultsToHorseAndNeverChangesRewards() {
        val db = Store(); val s = session(db); s.updateVehicle(parked)
        val before = s.state
        listOf("mine", "mining", "invalid", "gpu", "").forEach { s.selectTheme(it) }
        assertEquals("horse", s.state.themeId); assertEquals(before.totalPoints, s.state.totalPoints)
        assertEquals(before.currentAd, s.state.currentAd); assertEquals(before.remainingAdCount, s.state.remainingAdCount)
        assertEquals(before.tripCandidatePoints, s.state.tripCandidatePoints)
    }
    @Test fun closeRevokesPermissionAndRejectsCallbacksFromPreviousAccount() {
        val s = session(); s.updateVehicle(parked); s.close()
        assertFalse(s.state.playbackAllowed)
        assertEquals(PlaybackCode.CLOSED, s.onAdCompleted("mock-initial-1").code)
        s.updateVehicle(parked); assertFalse(s.state.playbackAllowed)
    }
    @Test fun fixtureIsIdempotentAndCandidatePointsDoNotDecreaseOnCredit() {
        val s = session(); s.enqueueMockAd("trip-ad"); s.enqueueMockAd("trip-ad")
        assertEquals(40, s.state.tripCandidatePoints); assertEquals(4, s.state.remainingAdCount)
        s.beginMockTrip("next-trip"); assertEquals(0, s.state.tripCandidatePoints)
        s.enqueueMockAd("next-ad"); s.beginMockTrip("next-trip"); assertEquals(10, s.state.tripCandidatePoints)
    }
    @Test fun corruptedCrossAccountRecordFailsClosedWithoutReplacingLedger() {
        val db = Store(); db.records["A"] = PlaybackAccountRecord("B")
        val s = session(db); assertFalse(s.state.storageAvailable); assertEquals("B", db.records["A"]?.accountId)
    }
}
