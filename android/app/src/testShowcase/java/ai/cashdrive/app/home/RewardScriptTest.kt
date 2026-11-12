package ai.cashdrive.app.home

import ai.cashdrive.core.playback.AdPlayback
import ai.cashdrive.core.playback.VehicleState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reward sequence's pure parts (plan C1, design §7, §13-4): which beats play and when, card 2's
 * wording for this trip's tickets, carry-over and the welcome ticket, the hand-off to the theater, the
 * runner that fires each beat once and nothing after a cancel (Review Focus 4), and the host's trip
 * mark that tells this trip's tickets at the arrival.
 */
class RewardScriptTest {
    private val day = "20261109"

    @Test fun noTicketsNoRemaining() {
        // Parked after a trip that earned nothing and with nothing left to watch: card 1 alone for 1.0 s, no briefing.
        val plan = RewardScript.arrival(ticketsThisTrip = 0, remaining = 0)
        assertEquals(listOf(RewardBeat.ARRIVE, RewardBeat.DISTANCE, RewardBeat.DISMISS), plan.steps.map { it.beat })
        assertEquals(RewardStep(RewardBeat.DISTANCE, 1_200, 1_000), plan.step(RewardBeat.DISTANCE))
        assertNull(plan.tickets)
        assertEquals(2_200L, plan.handOffMs)
    }

    @Test fun ticketsThisTrip() {
        val plan = RewardScript.arrival(ticketsThisTrip = 2, remaining = 2)
        assertEquals(
            listOf(
                RewardStep(RewardBeat.ARRIVE, 0, 1_200),
                RewardStep(RewardBeat.DISTANCE, 1_200, 700),
                RewardStep(RewardBeat.TICKETS, 1_900, 700),
                RewardStep(RewardBeat.DISMISS, 2_600, 200),
            ),
            plan.steps,
        )
        assertEquals("시청권 2장", plan.tickets?.text)
        // The theater opens as the cards leave, 2.6 s after the arrival started.
        assertEquals(2_600L, plan.handOffMs)
        assertEquals(2_800L, plan.endMs)
    }

    @Test fun carryOverOnly() {
        // A trip that earned nothing, with three tickets left from before: card 2 counts those and the briefing plays.
        val plan = RewardScript.arrival(ticketsThisTrip = 0, remaining = 3)
        assertTrue(plan.has(RewardBeat.TICKETS))
        assertEquals("이어서 볼 시청권 3장", plan.tickets?.text)
        assertEquals(2_600L, plan.handOffMs)
    }

    @Test fun welcomeTicketAfterAShortFirstTrip() {
        val plan = RewardScript.arrival(ticketsThisTrip = 0, remaining = 1, welcome = true)
        assertEquals("환영 시청권 1장", plan.tickets?.text)
    }

    @Test fun ticketsEarnedOnTheTripOutrankTheWelcomeWording() {
        assertEquals("시청권 1장", RewardScript.arrival(ticketsThisTrip = 1, remaining = 2, welcome = true).tickets?.text)
    }

    @Test fun cardTwoNeverPromisesMoreThanCanBeWatched() {
        assertEquals("시청권 2장", RewardScript.arrival(ticketsThisTrip = 3, remaining = 2).tickets?.text)
        // Nothing left to watch: no ticket card, whatever the trip earned.
        assertFalse(RewardScript.arrival(ticketsThisTrip = 2, remaining = 0).has(RewardBeat.TICKETS))
    }

    @Test fun welcomeWithoutADriveShowsCardTwoForPointEightSeconds() {
        val plan = RewardScript.welcome()
        assertEquals(listOf(RewardStep(RewardBeat.TICKETS, 0, 800), RewardStep(RewardBeat.DISMISS, 800, 200)), plan.steps)
        assertEquals("환영 시청권 1장", plan.tickets?.text)
        assertEquals(800L, plan.handOffMs)
    }

    @Test fun chargeBonusShowsItsOwnTicketCardForOneSecondBeforeTheTheater() {
        // A charge port connected in park earns a ticket; it gets the reward card, not a toast over the theater.
        val plan = RewardScript.chargeBonus()
        assertEquals(listOf(RewardStep(RewardBeat.TICKETS, 0, 1_000), RewardStep(RewardBeat.DISMISS, 1_000, 200)), plan.steps)
        assertEquals("충전 보너스 1장", plan.tickets?.text)
        assertEquals(1_000L, plan.handOffMs)
        assertFalse(plan.has(RewardBeat.ARRIVE))
        assertFalse(plan.has(RewardBeat.DISTANCE))
    }

    @Test fun cardOneReadsTenthsOfAKilometre() {
        // Card 1 counts up in the meter's unit: whole tenths, rounded down like the odometer.
        assertEquals("0.0", RewardScript.distanceNumber(0))
        assertEquals("1.1", RewardScript.distanceNumber(11))
        assertEquals("12.3", RewardScript.distanceNumber(123))
        assertEquals("0.0", RewardScript.distanceNumber(-5))
    }

    @Test fun runFiresEachBeatOnceInOrderAndHandsOffAtTheDismiss() {
        val events = mutableListOf<String>()
        val run = RewardRun(RewardScript.arrival(ticketsThisTrip = 1, remaining = 1), recorder(events))
        assertEquals(0L, run.nextDueMs())
        run.advanceTo(0)
        assertEquals(listOf("ARRIVE"), events)
        assertEquals(1_200L, run.nextDueMs())
        run.advanceTo(1_250)
        run.advanceTo(1_250)
        assertEquals(listOf("ARRIVE", "DISTANCE"), events)
        assertEquals(1_900L, run.nextDueMs())
        // A late wake-up still plays every beat that came due, in order, once.
        run.advanceTo(10_000)
        assertEquals(listOf("ARRIVE", "DISTANCE", "TICKETS", "DISMISS", "handOff", "end"), events)
        assertNull(run.nextDueMs())
        assertTrue(run.done)
    }

    @Test fun handOffWaitsForTheDismiss() {
        val events = mutableListOf<String>()
        val run = RewardRun(RewardScript.arrival(ticketsThisTrip = 1, remaining = 1), recorder(events))
        run.advanceTo(2_599)
        assertFalse("handOff" in events)
        run.advanceTo(2_600)
        assertEquals(listOf("DISMISS", "handOff"), events.takeLast(2))
        assertEquals(2_800L, run.nextDueMs())
    }

    @Test fun cancelStopsCallbacks() {
        val events = mutableListOf<String>()
        val run = RewardRun(RewardScript.arrival(ticketsThisTrip = 1, remaining = 1), recorder(events))
        run.advanceTo(1_300)
        run.cancel()
        run.advanceTo(10_000)
        // Leaving park mid-sequence: no ticket card, no dismiss, and above all no hand-off (the theater never opens).
        assertEquals(listOf("ARRIVE", "DISTANCE"), events)
        assertNull(run.nextDueMs())
        assertTrue(run.done)
    }

    @Test fun cancelFromInsideABeatStopsTheRest() {
        val events = mutableListOf<String>()
        lateinit var run: RewardRun
        run = RewardRun(RewardScript.arrival(ticketsThisTrip = 1, remaining = 1), object : RewardRun.Listener {
            override fun onBeat(step: RewardStep) { events += step.beat.name; if (step.beat == RewardBeat.TICKETS) run.cancel() }
            override fun onHandOff() { events += "handOff" }
            override fun onEnd() { events += "end" }
        })
        run.advanceTo(10_000)
        assertEquals(listOf("ARRIVE", "DISTANCE", "TICKETS"), events)
    }

    @Test fun markIsTakenWhenDrivingStartsAndKeptThroughALight() {
        assertNull(RewardScript.mark(null, VehicleState.STOPPED_PARKED, issuedToday = 3, today = day))
        assertNull(RewardScript.mark(null, VehicleState.UNKNOWN, issuedToday = 3, today = day))
        val mark = RewardScript.mark(null, VehicleState.DRIVING, issuedToday = 3, today = day)
        assertEquals(TripMark(3, day), mark)
        // A light (D at 0 km/h) and driving on do not start a new trip: the first mark stays.
        assertEquals(mark, RewardScript.mark(mark, VehicleState.STOPPED_WAITING, issuedToday = 4, today = day))
        assertEquals(mark, RewardScript.mark(mark, VehicleState.DRIVING, issuedToday = 5, today = day))
    }

    @Test fun ticketsThisTripCountFromTheMark() {
        assertEquals(2, RewardScript.ticketsThisTrip(TripMark(3, day), issuedToday = 5, today = day))
        assertEquals(0, RewardScript.ticketsThisTrip(null, issuedToday = 5, today = day))
        assertEquals(0, RewardScript.ticketsThisTrip(TripMark(5, day), issuedToday = 3, today = day))
    }

    @Test fun aTripAcrossMidnightCountsTodaysIssue() {
        // Today's count restarted at 0 at midnight, so all of today's tickets came on this trip.
        assertEquals(2, RewardScript.ticketsThisTrip(TripMark(7, "20261108"), issuedToday = 2, today = day))
    }

    @Test fun markSurvivesTheProcessAndRejectsGarbage() {
        assertEquals(TripMark(3, day), TripMark.decode(TripMark(3, day).encode()))
        assertNull(TripMark.decode(null))
        assertNull(TripMark.decode(""))
        assertNull(TripMark.decode("3"))
        assertNull(TripMark.decode("x@$day"))
        assertNull(TripMark.decode("-1@$day"))
        assertNull(TripMark.decode("3@"))
    }

    @Test fun welcomeIsDueOnlyBeforeTheFirstPlayback() {
        val fresh = AdPlayback("t-welcome-01", positionMs = 0, durationMs = 15_000)
        assertTrue(RewardScript.welcomeDue(creditedCount = 0, current = fresh, welcomed = false))
        assertFalse(RewardScript.welcomeDue(creditedCount = 0, current = fresh.copy(positionMs = 1_200), welcomed = false))
        assertFalse(RewardScript.welcomeDue(creditedCount = 0, current = fresh, welcomed = true))
        assertFalse(RewardScript.welcomeDue(creditedCount = 1, current = fresh, welcomed = false))
        assertFalse(RewardScript.welcomeDue(creditedCount = 0, current = AdPlayback("t-$day-01", 0, 15_000), welcomed = false))
        assertFalse(RewardScript.welcomeDue(creditedCount = 0, current = null, welcomed = false))
    }

    private fun recorder(events: MutableList<String>) = object : RewardRun.Listener {
        override fun onBeat(step: RewardStep) { events += step.beat.name }
        override fun onHandOff() { events += "handOff" }
        override fun onEnd() { events += "end" }
    }
}
