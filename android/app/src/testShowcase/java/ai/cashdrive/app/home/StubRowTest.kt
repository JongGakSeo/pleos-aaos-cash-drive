package ai.cashdrive.app.home

import ai.cashdrive.core.playback.VehicleState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure functions behind the meter panel (plan B1, design §5, §13-5, §13-12): the stub row mapping,
 * the drum, gauge and label arithmetic the views draw from, and the decisions about when the panel may
 * move. No Android types, so these run on the plain JVM.
 */
class StubRowTest {
    private val cap = 10

    @Test fun firstDayWelcome() {
        // Welcome ticket before any distance: nothing issued today, one ticket carried in from nowhere.
        assertEquals(StubLayout(punched = 0, ready = 0, dotted = 10, extra = 1), StubRow.layout(issuedToday = 0, remaining = 1, cap = cap))
    }

    @Test fun threeIssuedOneWatched() {
        assertEquals(StubLayout(punched = 1, ready = 2, dotted = 7, extra = 0), StubRow.layout(issuedToday = 3, remaining = 2, cap = cap))
    }

    @Test fun carryOver() {
        // Two issued today but four left to watch: the two carried over show as a "+2" badge, never as negative cells.
        assertEquals(StubLayout(punched = 0, ready = 2, dotted = 8, extra = 2), StubRow.layout(issuedToday = 2, remaining = 4, cap = cap))
    }

    @Test fun cap() {
        assertEquals(StubLayout(punched = 10, ready = 0, dotted = 0, extra = 0), StubRow.layout(issuedToday = 12, remaining = 0, cap = cap))
    }

    @Test fun neverNegative() {
        assertEquals(StubLayout(punched = 0, ready = 0, dotted = 10, extra = 0), StubRow.layout(issuedToday = 0, remaining = 0, cap = cap))
        assertEquals(StubLayout(punched = 0, ready = 0, dotted = 10, extra = 0), StubRow.layout(issuedToday = -3, remaining = -1, cap = cap))
        assertEquals(StubLayout(punched = 0, ready = 0, dotted = 0, extra = 1), StubRow.layout(issuedToday = 0, remaining = 1, cap = 0))
    }

    @Test fun cellsAlwaysSumToTheCap() {
        for (issued in 0..14) for (remaining in 0..14) {
            val layout = StubRow.layout(issued, remaining, cap)
            assertEquals(cap, layout.punched + layout.ready + layout.dotted, "issued $issued remaining $remaining")
            assertTrue(layout.extra >= 0)
        }
    }

    @Test fun stubsShrinkToFitAndNeverOverflow() {
        // 44dp stubs, 8dp apart, a 64dp badge slot: 10 stubs need 512dp, with the badge 576dp.
        assertEquals(1f, StubRow.fitScale(available = 600f, cells = 10, stub = 44f, gap = 8f, badge = 64f))
        val scale = StubRow.fitScale(available = 400f, cells = 10, stub = 44f, gap = 8f, badge = 64f)
        assertTrue(scale < 1f)
        assertTrue(10 * 44f * scale + 9 * 8f * scale + 64f <= 400f + 0.01f)
        assertEquals(0f, StubRow.fitScale(available = 40f, cells = 10, stub = 44f, gap = 8f, badge = 64f))
        assertEquals(1f, StubRow.fitScale(available = 10f, cells = 0, stub = 44f, gap = 8f, badge = 0f))
    }

    @Test fun theWideMeterLaysTodaysTicketsOutAsTwoRowsOfFive() {
        // 565dp of row, 12dp apart, at most 104dp wide and 1.6 times as wide as tall.
        val grid = StubRow.grid(available = 565f, cells = 10, perRow = 5, maxStub = 104f, gap = 12f, aspect = 1.6f)
        assertEquals(5, grid.perRow)
        assertEquals(2, grid.rows)
        assertEquals(103.4f, grid.stubWidth, 0.01f)
        assertEquals(103.4f / 1.6f, grid.stubHeight, 0.01f)
        assertEquals(2 * grid.stubHeight + 12f, grid.height, 0.01f)
        // A wider row stops growing at the largest stub.
        assertEquals(104f, StubRow.grid(900f, 10, 5, 104f, 12f, 1.6f).stubWidth, 0.01f)
        // Never wider than the row.
        val narrow = StubRow.grid(300f, 10, 5, 104f, 12f, 1.6f)
        assertTrue(5 * narrow.stubWidth + 4 * 12f <= 300f + 0.01f)
    }

    @Test fun theGridKeepsEveryCellAndAnUnevenLastRow() {
        assertEquals(3, StubRow.grid(565f, 11, 5, 104f, 12f, 1.6f).rows)
        assertEquals(0, StubRow.grid(565f, 0, 5, 104f, 12f, 1.6f).rows)
        assertEquals(0f, StubRow.grid(565f, 0, 5, 104f, 12f, 1.6f).height)
        // No row length (0) or more per row than cells: one row of every cell.
        val single = StubRow.grid(565f, 10, 0, 104f, 12f, 1.6f)
        assertEquals(10, single.perRow); assertEquals(1, single.rows)
        assertEquals(10, StubRow.grid(565f, 10, 12, 104f, 12f, 1.6f).perRow)
        // Cell 7 of rows of five is the third in the second row.
        assertEquals(2 to 1, StubRow.slot(7, perRow = 5))
        assertEquals(0 to 0, StubRow.slot(0, perRow = 5))
        assertEquals(4 to 0, StubRow.slot(4, perRow = 5))
    }

    @Test fun odometerDrumsReadLikeATripMeter() {
        // Three integer drums and the tenths drum, as a car's trip meter: 000.0 up to 999.9, then one more drum.
        assertEquals(listOf(0, 0, 0, 0), MeterMath.drums(0))
        assertEquals(listOf(0, 0, 0, 5), MeterMath.drums(5))
        assertEquals(listOf(0, 1, 2, 3), MeterMath.drums(123))
        assertEquals(listOf(9, 9, 9, 9), MeterMath.drums(9_999))
        assertEquals(listOf(1, 0, 0, 0, 0), MeterMath.drums(10_000))
        assertEquals(listOf(0, 0, 0, 0), MeterMath.drums(-40))
    }

    @Test fun leadingZeroDrumsStayDimButTheUnitsDrumIsAlwaysLit() {
        assertEquals(2, MeterMath.firstLit(MeterMath.drums(0)))      // 0.0: units and tenths lit
        assertEquals(2, MeterMath.firstLit(MeterMath.drums(5)))      // 0.5
        assertEquals(2, MeterMath.firstLit(MeterMath.drums(12)))     // 1.2
        assertEquals(1, MeterMath.firstLit(MeterMath.drums(100)))    // 10.0: the zero after the 1 is lit
        assertEquals(1, MeterMath.firstLit(MeterMath.drums(123)))    // 12.3
        assertEquals(0, MeterMath.firstLit(MeterMath.drums(1_000)))  // 100.0
        assertEquals(0, MeterMath.firstLit(MeterMath.drums(4_831)))  // 483.1
        assertEquals(0, MeterMath.firstLit(MeterMath.drums(10_000)))
    }

    @Test fun tenthsRoundDownToTheMetreShown() {
        assertEquals(0L, MeterMath.tenths(99))
        assertEquals(1L, MeterMath.tenths(100))
        assertEquals(12L, MeterMath.tenths(1_299))
        assertEquals(0L, MeterMath.tenths(-5))
    }

    @Test fun litSegmentsFloorTheFractionIntoTenCells() {
        assertEquals(0, MeterMath.litSegments(0f))
        assertEquals(7, MeterMath.litSegments(0.7f))
        assertEquals(7, MeterMath.litSegments(0.79f))
        assertEquals(10, MeterMath.litSegments(1f))
        assertEquals(10, MeterMath.litSegments(1.5f))
        assertEquals(0, MeterMath.litSegments(-0.2f))
        assertEquals(3, MeterMath.litSegments(0.3f))
    }

    @Test fun gaugeFillsWithTheShareOfTheBlockDriven() {
        assertEquals(0f, MeterMath.gaugeFraction(1_000))
        assertEquals(0.7f, MeterMath.gaugeFraction(300), 1e-4f)
        assertEquals(1f, MeterMath.gaugeFraction(0))
        assertEquals(0f, MeterMath.gaugeFraction(1_500))
        assertEquals(1f, MeterMath.gaugeFraction(-5))
        assertEquals(7, MeterMath.litSegments(MeterMath.gaugeFraction(300)))
    }

    @Test fun distanceLeftRoundsUpToTheNextHundredMetres() {
        // "다음 시청권까지 0.7 km": the value never promises less than the truth.
        assertEquals("0.7 km", MeterMath.distanceLabel(700))
        assertEquals("0.7 km", MeterMath.distanceLabel(650))
        assertEquals("1.0 km", MeterMath.distanceLabel(1_000))
        assertEquals("1.0 km", MeterMath.distanceLabel(901))
        assertEquals("0.1 km", MeterMath.distanceLabel(1))
        assertEquals("0.0 km", MeterMath.distanceLabel(0))
        assertEquals("0.0 km", MeterMath.distanceLabel(-30))
    }

    @Test fun theCapReplacesTheGauge() {
        assertTrue(MeterMath.capped(issuedToday = 10, dailyCap = 10))
        assertTrue(MeterMath.capped(issuedToday = 12, dailyCap = 10))
        assertFalse(MeterMath.capped(issuedToday = 9, dailyCap = 10))
        assertFalse(MeterMath.capped(issuedToday = 0, dailyCap = 10))
    }

    @Test fun aTicketIsEarnedOnlyWhenTodaysIssueRises() {
        assertFalse(MeterMath.ticketEarned(previous = null, issuedToday = 3))   // first bind after launch
        assertTrue(MeterMath.ticketEarned(previous = 2, issuedToday = 3))
        assertFalse(MeterMath.ticketEarned(previous = 3, issuedToday = 3))
        assertFalse(MeterMath.ticketEarned(previous = 3, issuedToday = 0))      // the day rolled over
    }

    @Test fun motionLevelFollowsTheVehicleAndTheSettings() {
        // Parked: everything moves. Driving with the motion setting on: half length, no glow. Anything else
        // (setting off, waiting at a light, unknown) or reduce motion: values snap.
        assertEquals(MotionLevel.FULL, MotionLevel.of(VehicleState.STOPPED_PARKED, motionWhileDriving = true, reduceMotion = false))
        assertEquals(MotionLevel.FULL, MotionLevel.of(VehicleState.STOPPED_PARKED, motionWhileDriving = false, reduceMotion = false))
        assertEquals(MotionLevel.NONE, MotionLevel.of(VehicleState.STOPPED_PARKED, motionWhileDriving = true, reduceMotion = true))
        assertEquals(MotionLevel.HALF, MotionLevel.of(VehicleState.DRIVING, motionWhileDriving = true, reduceMotion = false))
        assertEquals(MotionLevel.NONE, MotionLevel.of(VehicleState.DRIVING, motionWhileDriving = false, reduceMotion = false))
        assertEquals(MotionLevel.NONE, MotionLevel.of(VehicleState.DRIVING, motionWhileDriving = true, reduceMotion = true))
        assertEquals(MotionLevel.NONE, MotionLevel.of(VehicleState.STOPPED_WAITING, motionWhileDriving = true, reduceMotion = false))
        assertEquals(MotionLevel.NONE, MotionLevel.of(VehicleState.UNKNOWN, motionWhileDriving = true, reduceMotion = false))
        assertEquals(1f, MotionLevel.FULL.scale)
        assertEquals(0.5f, MotionLevel.HALF.scale)
        assertEquals(0f, MotionLevel.NONE.scale)
    }

    @Test fun creditsCountUpAndGlowOnlyParkedAndOnlyUpward() {
        assertTrue(MeterMath.creditsAnimate(MotionLevel.FULL, from = 10, to = 20))
        assertFalse(MeterMath.creditsAnimate(MotionLevel.HALF, from = 10, to = 20))
        assertFalse(MeterMath.creditsAnimate(MotionLevel.NONE, from = 10, to = 20))
        assertFalse(MeterMath.creditsAnimate(MotionLevel.FULL, from = 20, to = 20))
        assertFalse(MeterMath.creditsAnimate(MotionLevel.FULL, from = 20, to = 0))
    }
}
