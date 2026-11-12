package ai.cashdrive.core

import ai.cashdrive.core.safety.*
import ai.cashdrive.core.rewards.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun parked(r: SafetyReducer, generation: Long = 1): SafetySnapshot {
        r.beginConnection(generation)
        r.onConnected(generation, 1)
        r.onUxRestrictions(generation, 2, false, 0)
        return r.onGear(generation, 3, GearEvidence.PARK)
    }
    @Test fun unknownUntilBothIndependentSignalsArrive() {
        val r = SafetyReducer()
        assertEquals(SafetyState.UNKNOWN, r.current().state)
        r.beginConnection(1); r.onConnected(1, 1)
        assertEquals(SafetyState.UNKNOWN, r.onGear(1, 2, GearEvidence.PARK).state)
        assertEquals(SafetyState.PARKED_ALLOWED, r.onUxRestrictions(1, 3, false, 0).state)
    }
    @Test fun gearNeverRelaxesUxRestrictions() {
        val r = SafetyReducer(); parked(r)
        assertEquals(SafetyState.RESTRICTED, r.onUxRestrictions(1, 4, true, 0).state)
        assertEquals(SafetyState.RESTRICTED, r.onGear(1, 5, GearEvidence.PARK).state)
        assertEquals(SafetyState.RESTRICTED, r.onUxRestrictions(1, 6, false, 16).state)
    }
    @Test fun idlingAndUnavailableNeverAuthorizeAds() {
        val r = SafetyReducer(); parked(r)
        assertEquals(SafetyState.STOPPED_UNQUALIFIED, r.onGear(1, 4, GearEvidence.NOT_PARK).state)
        assertFalse(r.current().mayOpenAd)
        assertEquals(SafetyState.UNKNOWN, r.onGear(1, 5, GearEvidence.UNKNOWN).state)
    }
    @Test fun oldCallbacksCannotUnlockAfterDisconnectOrReconnect() {
        val r = SafetyReducer(); parked(r)
        r.invalidate(2)
        r.onConnected(1, 100); r.onUxRestrictions(1, 101, false, 0); r.onGear(1, 102, GearEvidence.PARK)
        assertEquals(SafetyState.UNKNOWN, r.current().state)
        parked(r, 3)
        assertEquals(SafetyState.PARKED_ALLOWED, r.current().state)
        assertEquals(3L, r.onUxRestrictions(1, 103, true, 255).generation)
        assertEquals(SafetyState.PARKED_ALLOWED, r.current().state)
    }
    @Test fun outOfOrderCallbacksDoNotUndoNewRestrictions() {
        val r = SafetyReducer(); parked(r)
        r.onUxRestrictions(1, 5, true, 16)
        r.onUxRestrictions(1, 4, false, 0)
        assertEquals(SafetyState.RESTRICTED, r.current().state)
    }
    @Test fun unchangedSignalsStayValidUntilAnExplicitLifecycleInvalidation() {
        val r = SafetyReducer(); parked(r)
        repeat(100) { assertTrue(r.current().mayOpenAd) }
        r.invalidate(2)
        assertFalse(r.current().mayOpenAd)
    }
    @Test fun fractionalMetersCarryThroughAnExplicitRebootBaseline() {
        val a = DistanceAccumulator()
        var s = a.accept(DistanceState(), OdometerSample(10_000, 1_000, "boot-a")).state
        val first = a.accept(s, OdometerSample(10_600, 11_000, "boot-a"))
        assertEquals(0L, first.earnedBlocks); assertEquals(600L, first.state.residualMeters)
        val reboot = a.accept(first.state, OdometerSample(10_600, 50, "boot-b"))
        assertTrue(reboot.quarantined)
        s = a.rebase(reboot.state, OdometerSample(10_600, 50, "boot-b"))
        val next = a.accept(s, OdometerSample(11_000, 10_050, "boot-b"))
        assertEquals(1L, next.earnedBlocks); assertEquals(0L, next.state.residualMeters)
    }
    @Test fun lateOrRolledBackSamplesNeverMoveTheRewardBaselineBackwards() {
        val a = DistanceAccumulator()
        val original = a.rebase(DistanceState(), OdometerSample(1_000, 2_000))
        val late = a.accept(original, OdometerSample(900, 1_000))
        assertTrue(late.quarantined); assertEquals(1_000L, late.state.lastOdometerMeters)
        val next = a.accept(late.state, OdometerSample(1_100, 3_000))
        assertEquals(100L, next.acceptedMeters)
    }
    @Test fun gapsAndImpossibleSpeedsGenerateNoReward() {
        val a = DistanceAccumulator()
        val s = a.rebase(DistanceState(), OdometerSample(1_000, 1_000))
        assertTrue(a.accept(s, OdometerSample(6_000, 1_001)).quarantined)
        val gap = a.accept(s, OdometerSample(2_000, 200_000))
        assertTrue(gap.quarantined); assertEquals(0L, gap.earnedBlocks)
        val rebased = a.rebase(gap.state, OdometerSample(2_000, 200_000))
        assertEquals(0L, rebased.residualMeters)
    }
    @Test fun confirmationRequiresCompletedAdParkAndConnectivityAndCreditsOnlyOnce() {
        val w = RewardWallet("a"); w.addPending("one", 10)
        w.beginAd("one", SafetyState.PARKED_ALLOWED); w.adCompleted("one", SafetyState.PARKED_ALLOWED)
        for (state in listOf(SafetyState.UNKNOWN, SafetyState.RESTRICTED, SafetyState.STOPPED_UNQUALIFIED)) {
            assertThrows(IllegalArgumentException::class.java) { w.confirm("one", state, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { w.confirm("one", SafetyState.PARKED_ALLOWED, false) }
        repeat(20) { w.confirm("one", SafetyState.PARKED_ALLOWED, true) }
        assertEquals(10, w.snapshot().confirmedPoints); assertEquals(0, w.snapshot().pendingPoints)
    }
    @Test fun interruptedAndProcessDeathRestorePendingWhileCompletedReceiptsSurvive() {
        val w = RewardWallet("a"); w.addPending("one", 10); w.addPending("two", 10)
        w.beginAd("one", SafetyState.PARKED_ALLOWED)
        val restart = RewardWallet("a", w.snapshot())
        assertEquals(TicketStatus.PENDING, restart.snapshot().tickets[0].status)
        restart.beginAd("one", SafetyState.PARKED_ALLOWED); restart.adCompleted("one", SafetyState.PARKED_ALLOWED)
        val second = RewardWallet("a", restart.snapshot())
        assertEquals(TicketStatus.AWAITING_CONFIRMATION, second.snapshot().tickets[0].status)
        second.beginAd("two", SafetyState.PARKED_ALLOWED); second.interruptAd("two")
        assertEquals(20, second.snapshot().pendingPoints)
    }
    @Test fun accountMismatchRejectsBalanceAndTicketsInsteadOfLeakingThem() {
        val a = RewardWallet("a"); a.addPending("one", 10)
        val other = a.snapshot().copy(confirmedPoints = 100)
        assertThrows(IllegalArgumentException::class.java) { RewardWallet("b", other) }
        assertEquals(0, RewardWallet("b").snapshot().confirmedPoints)
    }
    @Test fun duplicateDistanceTicketCannotChangeRewardValue() {
        val w = RewardWallet("a"); repeat(10) { w.addPending("one", 10) }
        assertEquals(10, w.snapshot().pendingPoints)
        assertThrows(IllegalArgumentException::class.java) { w.addPending("one", 20) }
    }
}
