package ai.cashdrive.core.playback

import kotlin.math.roundToLong

/**
 * Durable trip-meter state for one account. Distances are metres estimated from vehicle speed
 * samples (not odometer readings); `last*` is the open integration window and is never persisted.
 * `dayKey` is the device-local calendar day (`YYYYMMDD`) that `todayMeters`/`issuedToday` belong to.
 */
data class MeterRecord(
    val dayKey: String = "",
    val todayMeters: Long = 0,
    val tripMeters: Long = 0,
    val residualMeters: Long = 0,
    val issuedToday: Int = 0,
    val tripOpen: Boolean = false,
    val chargeBonusGiven: Boolean = false,
    val revealPending: Boolean = false,
    val lastElapsedMs: Long? = null,
    val lastSpeedKph: Double? = null,
) {
    fun validate(): MeterRecord {
        require(todayMeters >= 0 && tripMeters >= 0 && residualMeters >= 0 && issuedToday >= 0)
        require((lastElapsedMs == null) == (lastSpeedKph == null))
        require(lastSpeedKph == null || (lastSpeedKph.isFinite() && lastSpeedKph >= 0))
        return this
    }
}

/** One meter step: the next record, the metres accepted into it, tickets issued by it, and whether the sample was discarded. */
data class MeterDelta(val record: MeterRecord, val acceptedMeters: Long, val newTickets: Int, val dropped: Boolean)

/**
 * Pure speed-integration meter: one ticket per [blockMeters] of accepted distance, at most [dailyCap]
 * tickets per day (including the charge bonus). No distance is ever invented: the first sample only
 * opens the window, and invalid speeds or gaps over [maxGapMs] discard the interval.
 */
class TripMeter(
    val blockMeters: Long = 1_000,
    val maxGapMs: Long = 5_000,
    val maxSpeedKph: Double = 200.0,
    val dailyCap: Int = 10,
) {
    init {
        require(blockMeters > 0 && maxGapMs > 0 && dailyCap > 0)
        require(maxSpeedKph.isFinite() && maxSpeedKph > 0)
    }

    /** Day boundary: daily totals restart, while the trip, the residual and the open window carry over. */
    fun roll(record: MeterRecord, dayKey: String): MeterRecord {
        val checked = check(record)
        return if (checked.dayKey == dayKey) checked
        else checked.copy(dayKey = dayKey, todayMeters = 0, issuedToday = 0, chargeBonusGiven = false)
    }

    /** Feed one speed sample (km/h, null = unavailable) taken at [elapsedMs] on a monotonic clock. */
    fun advance(record: MeterRecord, dayKey: String, speedKph: Double?, elapsedMs: Long): MeterDelta {
        val base = roll(record, dayKey)
        if (speedKph == null || !speedKph.isFinite() || speedKph < 0 || speedKph > maxSpeedKph)
            return MeterDelta(base.copy(lastElapsedMs = null, lastSpeedKph = null), 0, 0, dropped = true)
        val lastMs = base.lastElapsedMs
        val lastSpeed = base.lastSpeedKph
        val window = base.copy(lastElapsedMs = elapsedMs, lastSpeedKph = speedKph)
        if (lastMs == null || lastSpeed == null) return MeterDelta(window, 0, 0, dropped = false)
        val dt = elapsedMs - lastMs
        if (dt <= 0 || dt > maxGapMs) return MeterDelta(window, 0, 0, dropped = true)
        // Trapezoid: mean of both speeds (km/h -> m/s) over the interval, rounded to whole metres.
        val meters = ((lastSpeed + speedKph) / 2.0 / 3.6 * dt / 1_000.0).roundToLong()
        val combined = Math.addExact(base.residualMeters, meters)
        val blocks = combined / blockMeters
        val newTickets = minOf(blocks, (dailyCap - base.issuedToday).toLong()).coerceAtLeast(0).toInt()
        val next = window.copy(
            todayMeters = Math.addExact(base.todayMeters, meters),
            tripMeters = Math.addExact(base.tripMeters, meters),
            residualMeters = combined % blockMeters,   // blocks beyond the daily cap are discarded
            issuedToday = base.issuedToday + newTickets,
            tripOpen = base.tripOpen || meters > 0,
        )
        return MeterDelta(next, meters, newTickets, dropped = false)
    }

    /** The vehicle left park: a new "this trip" starts and the parked-session bonus becomes available again. */
    fun openTrip(record: MeterRecord, dayKey: String): MeterRecord =
        roll(record, dayKey).copy(tripMeters = 0, tripOpen = true, chargeBonusGiven = false, revealPending = false)

    /** Parked after an open trip: close it and ask the UI for one reward reveal. A closed trip changes nothing. */
    fun requestReveal(record: MeterRecord): MeterRecord {
        val checked = check(record)
        return if (checked.tripOpen) checked.copy(tripOpen = false, revealPending = true) else checked
    }

    fun acknowledgeReveal(record: MeterRecord): MeterRecord = check(record).copy(revealPending = false)

    /** One bonus ticket per parked session while charging, still bounded by the daily cap. */
    fun chargeBonus(record: MeterRecord, dayKey: String): MeterDelta {
        val base = roll(record, dayKey)
        if (base.chargeBonusGiven || base.issuedToday >= dailyCap) return MeterDelta(base, 0, 0, dropped = false)
        return MeterDelta(base.copy(issuedToday = base.issuedToday + 1, chargeBonusGiven = true), 0, 1, dropped = false)
    }

    private fun check(record: MeterRecord): MeterRecord {
        record.validate()
        require(record.residualMeters < blockMeters)
        return record
    }
}
