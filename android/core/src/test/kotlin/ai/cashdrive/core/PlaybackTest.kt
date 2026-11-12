package ai.cashdrive.core

import ai.cashdrive.core.playback.*
import org.junit.Test
import kotlin.test.*

class PlaybackTest {
    private val parked = VehicleStatus(VehicleState.STOPPED_PARKED, 0.0, true, true, 0, false)
    private val waiting = parked.copy(vehicleState = VehicleState.STOPPED_WAITING,
        requiresDistractionOptimization = true, activeRestrictions = 64)
    private val driving = parked.copy(vehicleState = VehicleState.DRIVING, speedKph = 40.0,
        activeRestrictions = 0x1FF, requiresDistractionOptimization = true)
    private val welcome = MockPlaybackSession.WELCOME_TICKET
    private class Store : PlaybackAccountStore {
        val records = mutableMapOf<String, PlaybackAccountRecord>()
        var failBefore = false
        var loseResponse = false
        var writes = 0
        @Synchronized override fun read(accountId: String) = records[accountId]
        @Synchronized override fun update(accountId: String, change: (PlaybackAccountRecord?) -> PlaybackAccountRecord): PlaybackAccountRecord {
            if (failBefore) error("disk failure")
            val next = change(records[accountId]).validate()
            records[accountId] = next; writes++
            if (loseResponse) { loseResponse = false; error("durable commit, response lost") }
            return next
        }
    }
    private fun session(store: Store = Store(), account: String = "A") = MockPlaybackSession(account, store)
    /** Welcome ticket plus two explicit fixtures: three ads, as the queue tests always had. */
    private fun seeded(store: Store = Store(), account: String = "A") =
        session(store, account).apply { enqueueMockAd("mock-2"); enqueueMockAd("mock-3") }
    private fun metered(store: Store = Store(), dayKey: () -> String = { "20261108" }) =
        MockPlaybackSession("A", store, dayKey = dayKey)

    @Test fun onlyParkedStopAllowsPlayback() {
        assertTrue(parked.playbackAllowed)
        assertFalse(waiting.playbackAllowed)
        assertFalse(waiting.copy(activeRestrictions = 0, requiresDistractionOptimization = false).playbackAllowed)
        assertFalse(waiting.parkedActionsAllowed)
    }
    @Test fun chargePortDoesNotChangeGates() {
        assertTrue(parked.copy(chargePortConnected = true).playbackAllowed)
        assertTrue(parked.copy(chargePortConnected = null).playbackAllowed)
        assertEquals(parked.copy(chargePortConnected = false).parkedActionsAllowed, parked.parkedActionsAllowed)
        assertFalse(waiting.copy(chargePortConnected = true).playbackAllowed)
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
        assertFalse(s.state.playbackAllowed); assertEquals(welcome, s.state.currentAd?.adId)
        s.updateVehicle(waiting); assertFalse(s.state.playbackAllowed)
        s.updateVehicle(parked); assertTrue(s.state.playbackAllowed)
        assertEquals(1, s.state.remainingAdCount)
    }
    @Test fun completionAutomaticallyCreditsTenAndAdvancesWithoutConfirmation() {
        val s = seeded(); s.updateVehicle(parked)
        assertEquals(0, s.state.creditedCount)
        assertEquals(PlaybackCode.OK, s.onAdCompleted(welcome).code)
        assertEquals(10, s.state.totalPoints); assertEquals(2, s.state.remainingAdCount)
        assertEquals("mock-2", s.state.currentAd?.adId); assertEquals(listOf("mock-2", "mock-3"), s.state.pendingAdIds)
        assertEquals(1, s.state.creditedCount)
        assertEquals(20, s.state.tripCandidatePoints)
    }
    @Test fun departureDeniesCompletionButSavesFinalPositionAndResumes() {
        val s = session(); s.updateVehicle(parked); s.onPlaybackPosition(welcome, 4200)
        s.updateVehicle(parked.copy(vehicleState = VehicleState.DRIVING, speedKph = 30.0))
        assertFalse(s.state.playbackAllowed)
        assertEquals(PlaybackCode.LOCKED, s.onAdCompleted(welcome).code)
        assertEquals(PlaybackCode.OK, s.onPlaybackPosition(welcome, 4250).code)
        s.updateVehicle(waiting); assertFalse(s.state.playbackAllowed)
        assertEquals(PlaybackCode.LOCKED, s.onAdCompleted(welcome).code)
        s.updateVehicle(parked); assertTrue(s.state.playbackAllowed)
        assertEquals(4250, s.state.currentAd?.positionMs)
    }
    @Test fun restartPreservesPositionAndRemainingButDoesNotRestoreSafety() {
        val db = Store(); seeded(db).apply { updateVehicle(parked); onPlaybackPosition(welcome, 3210); close() }
        val restored = session(db)
        assertEquals(3210, restored.state.currentAd?.positionMs); assertEquals(3, restored.state.remainingAdCount)
        assertFalse(restored.state.playbackAllowed)
    }
    @Test fun accountSwitchSeparatesThemePositionAndLedger() {
        val db = Store(); val a = seeded(db); a.updateVehicle(parked)
        a.selectTheme("mining"); a.onAdCompleted(welcome); a.onPlaybackPosition("mock-2", 2500); a.close()
        val b = session(db, "B"); b.updateVehicle(parked); b.selectTheme("mine")
        assertEquals(0, b.state.totalPoints); assertEquals(0, b.state.currentAd?.positionMs)
        assertEquals(listOf(welcome), b.state.pendingAdIds)
        val restored = session(db)
        assertEquals("mining", restored.state.themeId); assertEquals(10, restored.state.totalPoints)
        assertEquals(2500, restored.state.currentAd?.positionMs); assertEquals(2, restored.state.remainingAdCount)
    }
    @Test fun duplicateCompletionAcrossSessionsAndRestartCreditsOnce() {
        val db = Store(); val a = session(db); val other = session(db)
        a.updateVehicle(parked); other.updateVehicle(parked)
        a.onAdCompleted(welcome); other.onAdCompleted(welcome)
        repeat(4) { a.onAdCompleted(welcome) }
        assertEquals(10, session(db).state.totalPoints); assertEquals(1, db.read("A")!!.ledger.size)
    }
    @Test fun responseLossAfterCommitThenRetryCannotDoubleCredit() {
        val db = Store(); val a = seeded(db); a.updateVehicle(parked); db.loseResponse = true
        assertEquals(PlaybackCode.STORAGE_ERROR, a.onAdCompleted(welcome).code)
        assertFalse(a.state.playbackAllowed)
        val reloaded = session(db); reloaded.updateVehicle(parked)
        assertEquals(PlaybackCode.OK, reloaded.onAdCompleted(welcome).code)
        assertEquals(10, reloaded.state.totalPoints); assertEquals(2, reloaded.state.remainingAdCount)
    }
    @Test fun failedCommitNeverCreditsOrPermitsFurtherPlayback() {
        val db = Store(); val s = session(db); s.updateVehicle(parked); db.failBefore = true
        assertEquals(PlaybackCode.STORAGE_ERROR, s.onAdCompleted(welcome).code)
        assertEquals(0, s.state.totalPoints); assertFalse(s.state.playbackAllowed)
        db.failBefore = false; assertEquals(0, session(db).state.totalPoints)
    }
    @Test fun outOfOrderProgressNeverRewindsAndFutureAdCannotComplete() {
        val s = seeded(); s.updateVehicle(parked)
        s.onPlaybackPosition(welcome, 3000); s.onPlaybackPosition(welcome, 1000)
        assertEquals(3000, s.state.currentAd?.positionMs)
        assertEquals(PlaybackCode.INVALID_EVENT, s.onAdCompleted("mock-2").code)
        assertEquals(PlaybackCode.INVALID_EVENT, s.onPlaybackPosition(welcome, 15001).code)
        assertEquals(PlaybackCode.UNKNOWN_AD, s.onAdCompleted("missing").code)
    }
    @Test fun exhaustedQueueStaysEmptyAfterRestart() {
        val db = Store(); val s = seeded(db); s.updateVehicle(parked)
        listOf(welcome, "mock-2", "mock-3").forEach { s.onAdCompleted(it) }
        val restored = session(db); assertEquals(30, restored.state.totalPoints)
        assertNull(restored.state.currentAd); assertEquals(0, restored.state.remainingAdCount)
        assertEquals(emptyList(), restored.state.pendingAdIds); assertEquals(3, restored.state.creditedCount)
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
        assertEquals(PlaybackCode.CLOSED, s.onAdCompleted(welcome).code)
        assertEquals(PlaybackCode.CLOSED, s.onSpeedSample(40.0, 1_000).code)
        assertEquals(PlaybackCode.CLOSED, s.acknowledgeReveal().code)
        s.updateVehicle(parked); assertFalse(s.state.playbackAllowed)
    }
    @Test fun fixtureIsIdempotentAndCandidatePointsDoNotDecreaseOnCredit() {
        val s = session(); s.enqueueMockAd("trip-ad"); s.enqueueMockAd("trip-ad")
        assertEquals(10, s.state.tripCandidatePoints); assertEquals(2, s.state.remainingAdCount)
        s.beginMockTrip("next-trip"); assertEquals(0, s.state.tripCandidatePoints)
        s.enqueueMockAd("next-ad"); s.beginMockTrip("next-trip"); assertEquals(10, s.state.tripCandidatePoints)
    }
    @Test fun corruptedCrossAccountRecordFailsClosedWithoutReplacingLedger() {
        val db = Store(); db.records["A"] = PlaybackAccountRecord("B")
        val s = session(db); assertFalse(s.state.storageAvailable); assertEquals("B", db.records["A"]?.accountId)
    }
    @Test fun corruptedMeterFailsClosed() {
        assertFailsWith<IllegalArgumentException> { PlaybackAccountRecord("A", meter = MeterRecord(issuedToday = -1)).validate() }
        val db = Store(); db.records["A"] = PlaybackAccountRecord("A", meter = MeterRecord(residualMeters = 1_000))
        assertFalse(metered(db).state.storageAvailable)
    }

    // Trip meter wiring (Task 3)

    @Test fun freshAccountHasOneWelcomeTicketAndNoCredits() {
        val s = metered()
        assertEquals(listOf("t-welcome-01"), s.state.pendingAdIds); assertEquals("t-welcome-01", s.state.currentAd?.adId)
        assertEquals(0, s.state.totalPoints); assertEquals(0, s.state.tripCandidatePoints); assertEquals(0, s.state.creditedCount)
        assertEquals(TripSummary(0, 0, 1_000, 0, 10, false, null), s.state.trip)
    }
    @Test fun drivingSamplesIssueTicketsWithDatedIds() {
        val db = Store(); val s = metered(db); s.updateVehicle(driving)
        repeat(61) { s.onSpeedSample(72.0, it * 1_000L) }
        assertEquals(2, s.state.remainingAdCount)
        assertEquals(listOf("t-welcome-01", "t-20261108-01"), s.state.pendingAdIds)
        assertEquals(800, s.state.trip.nextTicketMeters); assertEquals(1, s.state.trip.issuedToday)
        assertEquals(1_200, s.state.trip.tripMeters); assertEquals(1_200, s.state.trip.todayMeters)
        assertEquals(AdPlayback("t-20261108-01", 0, 15_000), db.read("A")!!.ads.last())
    }
    @Test fun parkingAfterTripRequestsRevealOnceAndAcknowledgeClears() {
        val s = metered(); s.updateVehicle(driving)
        repeat(3) { s.onSpeedSample(36.0, it * 1_000L) }
        s.updateVehicle(parked)
        assertTrue(s.state.trip.revealPending); assertEquals(20, s.state.trip.tripMeters)
        assertEquals(PlaybackCode.OK, s.acknowledgeReveal().code); assertFalse(s.state.trip.revealPending)
        s.updateVehicle(parked); assertFalse(s.state.trip.revealPending)
    }
    @Test fun shortWaitingDoesNotRequestReveal() {
        val s = metered(); s.updateVehicle(parked); s.acknowledgeReveal()
        s.updateVehicle(waiting); s.updateVehicle(parked)
        assertFalse(s.state.trip.revealPending)
    }
    @Test fun leavingParkStartsANewTripAndDepartureCancelsAPendingReveal() {
        val s = metered(); s.updateVehicle(driving)
        repeat(3) { s.onSpeedSample(36.0, it * 1_000L) }
        s.updateVehicle(parked); assertTrue(s.state.trip.revealPending)
        s.updateVehicle(driving)                                   // left before the UI acknowledged
        assertFalse(s.state.trip.revealPending); assertEquals(0, s.state.trip.tripMeters); assertEquals(20, s.state.trip.todayMeters)
        repeat(2) { s.onSpeedSample(36.0, 10_000L + it * 1_000L) }
        assertEquals(10, s.state.trip.tripMeters); assertEquals(30, s.state.trip.todayMeters)
    }
    @Test fun unknownChargePortNeverGrantsBonus() {
        val s = metered(); s.updateVehicle(parked.copy(chargePortConnected = null))
        assertEquals(1, s.state.remainingAdCount); assertNull(s.state.trip.charging)
        s.updateVehicle(parked.copy(chargePortConnected = false))
        assertEquals(1, s.state.remainingAdCount); assertEquals(false, s.state.trip.charging)
        s.updateVehicle(parked.copy(chargePortConnected = true)); assertEquals(2, s.state.remainingAdCount)
        assertEquals(listOf("t-welcome-01", "t-20261108-01"), s.state.pendingAdIds); assertEquals(true, s.state.trip.charging)
        s.updateVehicle(parked.copy(chargePortConnected = true)); assertEquals(2, s.state.remainingAdCount)
        assertEquals(1, s.state.trip.issuedToday)
    }
    @Test fun chargeBonusNeedsParkNotJustACable() {
        val s = metered()
        s.updateVehicle(waiting.copy(chargePortConnected = true)); s.updateVehicle(driving.copy(chargePortConnected = true))
        s.updateVehicle(VehicleStatus(chargePortConnected = true))
        assertEquals(1, s.state.remainingAdCount)
    }
    @Test fun samplesWithoutConnectedPermittedVehicleAreDropped() {
        val s = metered()
        repeat(3) { s.onSpeedSample(72.0, it * 1_000L) }        // vehicle still UNKNOWN: nothing accrues
        assertEquals(0, s.state.trip.todayMeters)
        s.updateVehicle(driving.copy(speedPermissionGranted = false)); s.onSpeedSample(72.0, 3_000)
        assertEquals(0, s.state.trip.todayMeters)
        s.updateVehicle(driving)
        repeat(3) { s.onSpeedSample(72.0, 4_000L + it * 1_000L) } // first sample only reopens the window
        assertEquals(40, s.state.trip.todayMeters)
    }
    @Test fun parkedSamplesDoNotWriteTheStore() {
        val db = Store(); val s = metered(db); s.updateVehicle(parked)
        s.onSpeedSample(0.0, 0)                                   // opens the window and stamps today's dayKey
        val before = db.writes
        repeat(5) { s.onSpeedSample(0.0, 1_000L + it * 1_000L) }
        assertEquals(before, db.writes); assertEquals(0, s.state.trip.todayMeters)
        s.updateVehicle(driving); s.onSpeedSample(36.0, 12_000); s.onSpeedSample(36.0, 13_000)
        assertEquals(10, s.state.trip.todayMeters); assertEquals(10, db.read("A")!!.meter.todayMeters)
    }
    @Test fun meterSurvivesStoreRoundTrip() {
        val store = Store(); val s = metered(store); s.updateVehicle(driving)
        repeat(31) { s.onSpeedSample(72.0, it * 1_000L) }
        val again = metered(store); assertEquals(600, again.state.trip.todayMeters); assertEquals(400, again.state.trip.nextTicketMeters)
        // A new session never inherits the old integration window: its first sample only opens one.
        again.updateVehicle(driving); again.onSpeedSample(72.0, 31_000)
        assertEquals(600, again.state.trip.todayMeters)
    }
    @Test fun ticketIdsStayUniqueWhenTheClockRollsBack() {
        var day = "20261108"
        val s = metered(dayKey = { day }); s.updateVehicle(driving)
        repeat(51) { s.onSpeedSample(72.0, it * 1_000L) }                 // 1,000 m -> t-20261108-01
        day = "20261109"; repeat(50) { s.onSpeedSample(72.0, 51_000L + it * 1_000L) }
        day = "20261108"; repeat(50) { s.onSpeedSample(72.0, 101_000L + it * 1_000L) }
        assertTrue(s.state.storageAvailable)
        assertEquals(listOf("t-welcome-01", "t-20261108-01", "t-20261109-01", "t-20261108-02"), s.state.pendingAdIds)
    }
    @Test fun dailyCapStopsTicketsButNotDistance() {
        val s = metered(); s.updateVehicle(driving)
        repeat(601) { s.onSpeedSample(72.0, it * 1_000L) }                // 12,000 m
        assertEquals(11, s.state.remainingAdCount); assertEquals(10, s.state.trip.issuedToday)
        assertEquals(0, s.state.trip.nextTicketMeters); assertEquals(12_000, s.state.trip.todayMeters)
        s.updateVehicle(parked.copy(chargePortConnected = true)); assertEquals(11, s.state.remainingAdCount)
    }
    @Test fun issuedTicketsPlayAndCreditLikeAnyAd() {
        val s = metered(); s.updateVehicle(driving)
        repeat(51) { s.onSpeedSample(72.0, it * 1_000L) }
        s.updateVehicle(parked); s.acknowledgeReveal()
        assertEquals(PlaybackCode.OK, s.onAdCompleted("t-welcome-01").code)
        assertEquals("t-20261108-01", s.state.currentAd?.adId)
        assertEquals(PlaybackCode.OK, s.onAdCompleted("t-20261108-01").code)
        assertEquals(20, s.state.totalPoints); assertEquals(0, s.state.remainingAdCount)
    }
}
