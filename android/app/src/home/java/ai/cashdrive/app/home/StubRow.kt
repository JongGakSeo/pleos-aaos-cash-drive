package ai.cashdrive.app.home

import ai.cashdrive.core.playback.VehicleState
import kotlin.math.floor

/**
 * Cells of the ticket stub row, left to right: [punched] stubs already watched (amber outline with a
 * hole), [ready] stubs that can be watched now (filled amber), [dotted] cells not issued yet today
 * (drawn as empty grey stubs). The three always add up to the daily cap. [extra] counts tickets beyond
 * today's issue (carry-over or the welcome ticket) and is shown as a "+N" badge, never as negative cells.
 */
data class StubLayout(val punched: Int, val ready: Int, val dotted: Int, val extra: Int)

/** Pure mapping from the session's counters to the stub row (design §13-5). No Android dependency. */
object StubRow {
    fun layout(issuedToday: Int, remaining: Int, cap: Int): StubLayout {
        val cells = cap.coerceAtLeast(0)
        val issued = issuedToday.coerceAtLeast(0)
        val left = remaining.coerceAtLeast(0)
        val punched = (issued - left).coerceAtLeast(0).coerceAtMost(cells)
        val ready = minOf(issued, left).coerceAtMost(cells - punched)
        return StubLayout(punched, ready, cells - punched - ready, (left - issued).coerceAtLeast(0))
    }

    /**
     * Scale (0..1) for [cells] stubs of width [stub] spaced by [gap] so that the row plus the [badge] slot
     * fits [available]: 1 when it fits, smaller when it would overflow, 0 when not even the badge fits.
     */
    fun fitScale(available: Float, cells: Int, stub: Float, gap: Float, badge: Float): Float {
        if (cells <= 0) return 1f
        val row = cells * stub + (cells - 1) * gap
        if (row <= 0f) return 1f
        return ((available - badge) / row).coerceIn(0f, 1f)
    }

    /**
     * The stubs as a grid of [perRow] per row (0, or more than [cells], is one row of every cell), [gap]
     * apart both ways: each stub as wide as the row allows up to [maxStub], [aspect] times as wide as tall.
     */
    fun grid(available: Float, cells: Int, perRow: Int, maxStub: Float, gap: Float, aspect: Float): StubGrid {
        val n = cells.coerceAtLeast(0)
        val across = if (perRow <= 0 || perRow > n) n else perRow
        if (n == 0 || across == 0) return StubGrid(0, 0, 0f, 0f, 0f)
        val rows = (n + across - 1) / across
        val width = ((available - (across - 1) * gap) / across).coerceIn(0f, maxStub)
        val height = if (aspect > 0f) width / aspect else 0f
        return StubGrid(across, rows, width, height, rows * height + (rows - 1) * gap)
    }

    /** Column and row of cell [index] in rows of [perRow]. */
    fun slot(index: Int, perRow: Int): Pair<Int, Int> {
        val across = perRow.coerceAtLeast(1)
        val i = index.coerceAtLeast(0)
        return (i % across) to (i / across)
    }
}

/** A stub grid: [perRow] across, [rows] down, each stub [stubWidth] by [stubHeight], [height] in all. */
data class StubGrid(val perRow: Int, val rows: Int, val stubWidth: Float, val stubHeight: Float, val height: Float)

/**
 * How much the meter may move (design §5, §13-12). [scale] multiplies every animation length.
 * Parked: everything at full length. Driving with the motion setting on: half length and no glow or
 * count-up. Otherwise (setting off while driving, waiting at a light, unknown) or with reduce motion:
 * values snap, nothing moves. The decision is [of]; the views only apply it.
 */
enum class MotionLevel(val scale: Float) {
    FULL(1f), HALF(0.5f), NONE(0f);

    companion object {
        fun of(vehicle: VehicleState, motionWhileDriving: Boolean, reduceMotion: Boolean): MotionLevel = when {
            reduceMotion -> NONE
            vehicle == VehicleState.STOPPED_PARKED -> FULL
            vehicle == VehicleState.DRIVING && motionWhileDriving -> HALF
            else -> NONE
        }
    }
}

/**
 * Everything the meter panel shows, as the host computed it. [parked] picks today's total over the trip
 * distance for the odometer (the old three-figure panel did the same). Display data only: the session
 * owns the counters and the host owns the texts.
 */
data class MeterModel(
    val tripMeters: Long,
    val todayMeters: Long,
    val nextTicketMeters: Long,
    val remaining: Int,
    val issuedToday: Int,
    val dailyCap: Int,
    val credits: Int,
    val contextLine: String?,
    val hint: String?,
    val parked: Boolean = false,
)

/** The arithmetic the meter views draw from, kept out of the views so it runs in JVM tests. */
object MeterMath {
    /** Cells of the ticket gauge: one per 100 m of the 1 km block. */
    const val SEGMENTS = 10

    /** Ticket block of the trip meter, for the gauge fraction only; the session owns the real value. */
    const val BLOCK_METERS = 1_000L

    /** Drums of the odometer window: three integer drums and the tenths drum, like a car's trip meter (000.0). */
    const val DRUMS = 4

    /** Metres to the 0.1 km unit the odometer shows, rounded down; never negative. */
    fun tenths(meters: Long): Long = meters.coerceAtLeast(0) / 100

    /** Drum digits of a tenths value with leading zeros, the last one being the tenths: 123 -> 0 1 2 3 (012.3). */
    fun drums(tenths: Long, count: Int = DRUMS): List<Int> =
        tenths.coerceAtLeast(0).toString().padStart(count.coerceAtLeast(2), '0').map { it - '0' }

    /** Index of the first drum drawn at full strength: leading zeros stay dim, the units and tenths drums never do. */
    fun firstLit(drums: List<Int>): Int {
        val units = (drums.size - 2).coerceAtLeast(0)
        for (i in 0 until units) if (drums[i] != 0) return i
        return units
    }

    /** Lit cells for a 0..1 fraction of the block: floor into [count] cells, clamped. */
    fun litSegments(fraction: Float, count: Int = SEGMENTS): Int =
        floor(fraction.coerceIn(0f, 1f) * count + 1e-4f).toInt().coerceIn(0, count)

    /** Share of the current block already driven (0..1) from the metres still needed for the next ticket. */
    fun gaugeFraction(nextTicketMeters: Long): Float =
        (1f - nextTicketMeters / BLOCK_METERS.toFloat()).coerceIn(0f, 1f)

    /** "0.7 km": metres left rounded up to the next 100 m, so the label never promises less than the truth. */
    fun distanceLabel(meters: Long): String {
        val hundreds = (meters.coerceAtLeast(0) + 99) / 100
        return "${hundreds / 10}.${hundreds % 10} km"
    }

    /** Today's tickets are all issued: no distance earns one any more, so the gauge gives way to one line. */
    fun capped(issuedToday: Int, dailyCap: Int): Boolean = issuedToday >= dailyCap

    /** A ticket was just issued: today's issue count rose since the last bind (never on the first bind or a new day). */
    fun ticketEarned(previous: Int?, issuedToday: Int): Boolean = previous != null && issuedToday > previous

    /** Credits count up with one glow only in park and only when they grow; every other change snaps. */
    fun creditsAnimate(level: MotionLevel, from: Int, to: Int): Boolean = level == MotionLevel.FULL && to > from
}
