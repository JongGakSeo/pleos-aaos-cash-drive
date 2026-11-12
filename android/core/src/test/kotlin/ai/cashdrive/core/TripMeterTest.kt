package ai.cashdrive.core

import ai.cashdrive.core.playback.*
import org.junit.Test
import kotlin.test.*

class TripMeterTest {
    private val meter = TripMeter()
    private val day = "20261108"
    private fun run(speeds: List<Double>, stepMs: Long = 1_000, start: MeterRecord = MeterRecord()): Pair<MeterRecord, Int> {
        var r = start; var tickets = 0
        speeds.forEachIndexed { i, v -> meter.advance(r, day, v, i * stepMs).also { r = it.record; tickets += it.newTickets } }
        return r to tickets
    }
    @Test fun firstSampleOnlyOpensWindow() {
        val d = meter.advance(MeterRecord(), day, 40.0, 1_000)
        assertEquals(0, d.acceptedMeters); assertFalse(d.dropped); assertEquals(1_000, d.record.lastElapsedMs)
        assertEquals(0, d.newTickets); assertEquals(0, d.record.tripMeters); assertFalse(d.record.tripOpen)
    }
    @Test fun integratesTrapezoidBetweenSamples() {
        val (r, _) = run(listOf(36.0, 36.0, 36.0))
        assertEquals(20, r.todayMeters); assertEquals(20, r.tripMeters); assertTrue(r.tripOpen)
    }
    @Test fun issuesTicketPerBlockAndCarriesResidual() {
        val (r, tickets) = run(List(61) { 72.0 })            // 60 s × 20 m/s = 1,200 m
        assertEquals(1, tickets); assertEquals(200, r.residualMeters); assertEquals(1, r.issuedToday)
    }
    @Test fun dropsGapOverFiveSecondsAndResetsWindow() {
        var r = meter.advance(MeterRecord(), day, 50.0, 0).record
        val gap = meter.advance(r, day, 50.0, 6_000)
        assertTrue(gap.dropped); assertEquals(0, gap.acceptedMeters); assertEquals(6_000, gap.record.lastElapsedMs)
        assertEquals(14, meter.advance(gap.record, day, 50.0, 7_000).acceptedMeters)
        r = gap.record
        val backwards = meter.advance(r, day, 50.0, 5_000)   // out-of-order sample: dropped, window moves
        assertTrue(backwards.dropped); assertEquals(0, backwards.acceptedMeters); assertEquals(5_000, backwards.record.lastElapsedMs)
    }
    @Test fun rejectsNullNanNegativeOverCap() {
        val open = meter.advance(MeterRecord(), day, 50.0, 0).record
        listOf(null, Double.NaN, -1.0, 201.0, Double.POSITIVE_INFINITY).forEach { v ->
            val d = meter.advance(open, day, v, 1_000)
            assertTrue(d.dropped, "$v"); assertEquals(0, d.acceptedMeters); assertNull(d.record.lastElapsedMs)
            assertNull(d.record.lastSpeedKph)
        }
        assertFalse(meter.advance(open, day, 200.0, 1_000).dropped)   // the cap itself is still valid
    }
    @Test fun zeroSpeedAddsNothing() {
        val (r, t) = run(listOf(0.0, 0.0, 0.0)); assertEquals(0, r.todayMeters); assertEquals(0, t); assertFalse(r.tripOpen)
    }
    @Test fun dailyCapTen() {
        val (r, tickets) = run(List(601) { 72.0 })           // 600 s × 20 m/s = 12,000 m
        assertEquals(10, tickets); assertEquals(10, r.issuedToday); assertEquals(12_000, r.todayMeters)
        assertEquals(0, meter.chargeBonus(r, day).newTickets)
        assertTrue(r.residualMeters in 0 until 1_000)
    }
    @Test fun rollResetsDailyFieldsKeepsTripAndResidual() {
        val r = MeterRecord(dayKey = day, todayMeters = 5_000, tripMeters = 700, residualMeters = 700, issuedToday = 4, tripOpen = true, chargeBonusGiven = true)
        val next = meter.roll(r, "20261109")
        assertEquals(0, next.todayMeters); assertEquals(0, next.issuedToday); assertFalse(next.chargeBonusGiven)
        assertEquals(700, next.tripMeters); assertEquals(700, next.residualMeters); assertTrue(next.tripOpen); assertEquals("20261109", next.dayKey)
        assertEquals(r, meter.roll(r, day))                   // same day: untouched
    }
    @Test fun advanceAcrossMidnightKeepsIntegrating() {
        val open = meter.advance(MeterRecord(dayKey = day, todayMeters = 900, issuedToday = 3), day, 36.0, 0).record
        val d = meter.advance(open, "20261109", 36.0, 1_000)
        assertFalse(d.dropped); assertEquals(10, d.acceptedMeters)
        assertEquals("20261109", d.record.dayKey); assertEquals(10, d.record.todayMeters); assertEquals(0, d.record.issuedToday)
    }
    @Test fun chargeBonusOncePerParkedSessionAndUnderCap() {
        val first = meter.chargeBonus(MeterRecord(), day)
        assertEquals(1, first.newTickets); assertTrue(first.record.chargeBonusGiven); assertEquals(1, first.record.issuedToday)
        assertEquals(0, first.acceptedMeters); assertFalse(first.dropped)
        assertEquals(0, meter.chargeBonus(first.record, day).newTickets)
        assertFalse(meter.openTrip(first.record, day).chargeBonusGiven)
    }
    @Test fun revealFlags() {
        var r = meter.openTrip(MeterRecord(), day)
        assertTrue(r.tripOpen); assertFalse(r.revealPending); assertEquals(day, r.dayKey)
        val closed = MeterRecord(dayKey = day)
        assertEquals(closed, meter.requestReveal(closed))      // closed trip: no reveal
        r = run(listOf(36.0, 36.0), start = r).first
        val pending = meter.requestReveal(r)
        assertTrue(pending.revealPending); assertFalse(pending.tripOpen); assertEquals(10, pending.tripMeters)
        assertFalse(meter.acknowledgeReveal(pending).revealPending)
        assertEquals(0, meter.openTrip(pending, day).tripMeters); assertFalse(meter.openTrip(pending, day).revealPending)
    }
    @Test fun rejectsCorruptRecordsAndBadConfiguration() {
        assertFailsWith<IllegalArgumentException> { MeterRecord(todayMeters = -1).validate() }
        assertFailsWith<IllegalArgumentException> { MeterRecord(lastElapsedMs = 1_000).validate() }   // window needs both fields
        assertFailsWith<IllegalArgumentException> { meter.advance(MeterRecord(residualMeters = 1_000), day, 0.0, 0) }
        assertFailsWith<IllegalArgumentException> { TripMeter(dailyCap = 0) }
        assertFailsWith<IllegalArgumentException> { TripMeter(maxSpeedKph = Double.NaN) }
    }
}
