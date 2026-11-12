package ai.cashdrive.core.playback

import ai.cashdrive.core.session.ThemeIds

/**
 * Local mock ledger; not ad supply, provider verification, payment, or server balance.
 * Tickets (ads) are issued by the trip meter from speed samples; [dayKey] supplies the device-local
 * calendar day (`YYYYMMDD`) used for daily totals and ticket ids.
 */
class MockPlaybackSession(
    private val accountId: String,
    private val store: PlaybackAccountStore,
    private val dayKey: () -> String = { "" },
    private val meter: TripMeter = TripMeter(),
) : PlaybackSession, AutoCloseable {
    private var vehicle = VehicleStatus()
    private var available = true
    private var closed = false
    private val listeners = linkedSetOf<(PlaybackState) -> Unit>()
    private var record = PlaybackAccountRecord(accountId)

    init {
        require(accountId.isNotBlank())
        // A new session never inherits an integration window: its first sample only opens one.
        try { record = checked(store.update(accountId) { it ?: initialRecord(accountId) }).withWindow(MeterRecord()) }
        catch (_: Exception) { available = false }
    }

    @get:Synchronized override val state: PlaybackState get() {
        val credited = record.ledger.map { it.adId }.toSet()
        val pending = record.ads.filter { it.adId !in credited }
        val today = meter.roll(record.meter, dayKey())
        val summary = TripSummary(today.tripMeters, today.todayMeters,
            if (today.issuedToday >= meter.dailyCap) 0 else meter.blockMeters - today.residualMeters,
            today.issuedToday, meter.dailyCap, today.revealPending, vehicle.chargePortConnected)
        return PlaybackState(accountId, vehicle, record.totalPoints, record.tripCandidatePoints,
            pending.size, pending.firstOrNull(), ThemeIds.normalize(record.themeId), available,
            summary, pending.map { it.adId }, record.ledger.size)
    }

    @Synchronized override fun observe(listener: (PlaybackState) -> Unit): AutoCloseable {
        check(!closed)
        listeners += listener
        listener(state)
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    /**
     * Vehicle status from the host. Starting to move on a closed trip opens a new one; arriving in
     * park closes an open trip and requests one reveal; a confirmed charge port in park grants the
     * parked-session bonus ticket. Nothing here is a speed sample: distance only comes from onSpeedSample.
     */
    @Synchronized fun updateVehicle(status: VehicleStatus) {
        if (closed) return
        vehicle = status
        val day = dayKey()
        if (available && meterTransition(record, status, day) != record) transact { meterTransition(it, status, day) }
        else publish()
    }

    /** One speed sample (km/h, null = unavailable) at [elapsedMs] on a monotonic clock, about once a second. */
    @Synchronized fun onSpeedSample(speedKph: Double?, elapsedMs: Long): PlaybackResult {
        if (closed) return result(PlaybackCode.CLOSED)
        if (!available) return result(PlaybackCode.STORAGE_ERROR)
        val day = dayKey()
        // Samples are evidence only while the vehicle is connected with the speed permission granted.
        val speed = if (vehicle.connected && vehicle.speedPermissionGranted) speedKph else null
        val preview = meter.advance(record.meter, day, speed, elapsedMs)
        if (preview.record.withoutWindow() == record.meter.withoutWindow()) {
            // Only the process-local window moved: nothing durable or visible changed.
            record = record.copy(meter = preview.record)
            return result(PlaybackCode.OK)
        }
        return transact { current -> issue(current, meter.advance(current.meter, day, speed, elapsedMs), day) }
    }

    /** The UI finished the reward reveal. */
    @Synchronized fun acknowledgeReveal(): PlaybackResult {
        if (closed) return result(PlaybackCode.CLOSED)
        if (!record.meter.revealPending) return result(if (available) PlaybackCode.OK else PlaybackCode.STORAGE_ERROR)
        return transact { current -> current.copy(meter = meter.acknowledgeReveal(current.meter)) }
    }

    @Synchronized override fun onPlaybackPosition(adId: String, positionMs: Long): PlaybackResult {
        if (positionMs < 0) return result(PlaybackCode.INVALID_EVENT)
        return transact { current ->
            if (current.ledger.any { it.adId == adId }) return@transact current
            val ad = current.ads.firstOrNull { candidate -> current.ledger.none { it.adId == candidate.adId } }
            if (ad?.adId != adId) fail(if (current.ads.any { it.adId == adId }) PlaybackCode.INVALID_EVENT else PlaybackCode.UNKNOWN_AD)
            if (positionMs > ad.durationMs) fail(PlaybackCode.INVALID_EVENT)
            // Late progress cannot rewind the durable checkpoint after a pause/restart.
            current.copy(ads = current.ads.map { if (it.adId == adId) it.copy(positionMs = maxOf(it.positionMs, positionMs)) else it })
        }
    }

    @Synchronized override fun onAdCompleted(adId: String): PlaybackResult = transact { current ->
        // Lost responses, duplicate callbacks, and a second session all see the same ledger entry.
        if (current.ledger.any { it.adId == adId }) return@transact current
        if (!vehicle.playbackAllowed) fail(PlaybackCode.LOCKED)
        val ad = current.ads.firstOrNull { candidate -> current.ledger.none { it.adId == candidate.adId } }
        if (ad?.adId != adId) fail(if (current.ads.any { it.adId == adId }) PlaybackCode.INVALID_EVENT else PlaybackCode.UNKNOWN_AD)
        current.copy(ads = current.ads.map { if (it.adId == adId) it.copy(positionMs = it.durationMs) else it },
            ledger = current.ledger + MockCredit(adId))
    }

    @Synchronized override fun selectTheme(themeId: String): PlaybackResult = transact { current ->
        if (!vehicle.parkedActionsAllowed) fail(PlaybackCode.LOCKED)
        current.copy(themeId = ThemeIds.normalize(themeId))
    }

    /** Explicit mock fixture: not distance measurement. Never added again on restart/account return. */
    @Synchronized fun enqueueMockAd(adId: String, durationMs: Long = TICKET_DURATION_MS): PlaybackResult = transact { current ->
        if (adId.isBlank() || durationMs <= 0) fail(PlaybackCode.INVALID_EVENT)
        val existing = current.ads.find { it.adId == adId }
        if (existing != null) {
            if (existing.durationMs != durationMs) fail(PlaybackCode.INVALID_EVENT)
            current
        } else current.copy(ads = current.ads + AdPlayback(adId, 0, durationMs),
            tripCandidatePoints = Math.addExact(current.tripCandidatePoints, 10))
    }

    @Synchronized fun beginMockTrip(tripId: String): PlaybackResult = transact { current ->
        if (tripId.isBlank()) fail(PlaybackCode.INVALID_EVENT)
        if (tripId == current.tripId) current else current.copy(tripId = tripId, tripCandidatePoints = 0)
    }

    @Synchronized override fun close() {
        if (closed) return
        vehicle = VehicleStatus()
        closed = true
        publish()
        listeners.clear()
    }

    private fun meterTransition(current: PlaybackAccountRecord, status: VehicleStatus, day: String): PlaybackAccountRecord {
        var next = current.meter
        val moving = status.vehicleState == VehicleState.DRIVING || (status.speedKph?.let { it.isFinite() && it > 0 } == true)
        if (moving && !next.tripOpen) next = meter.openTrip(next, day)
        if (!status.playbackAllowed) return current.copy(meter = next)
        next = meter.requestReveal(next)
        if (status.chargePortConnected != true) return current.copy(meter = next)
        return issue(current, meter.chargeBonus(next, day), day)
    }

    /** Apply a meter step and append its new tickets in the same record (one atomic commit). */
    private fun issue(current: PlaybackAccountRecord, delta: MeterDelta, day: String): PlaybackAccountRecord {
        val ads = current.ads.toMutableList()
        val used = ads.mapTo(mutableSetOf()) { it.adId }
        var sequence = delta.record.issuedToday - delta.newTickets + 1
        repeat(delta.newTickets) {
            // Ids restart with the day; if the clock ever rolls back, skip ids this account already holds.
            var id = ticketId(day, sequence)
            while (!used.add(id)) { sequence++; id = ticketId(day, sequence) }
            ads += AdPlayback(id, 0, TICKET_DURATION_MS)
            sequence++
        }
        return current.copy(ads = ads, meter = delta.record)
    }

    private fun checked(value: PlaybackAccountRecord): PlaybackAccountRecord {
        require(value.accountId == accountId)
        value.validate()
        require(value.meter.residualMeters < meter.blockMeters)
        return value
    }

    private fun transact(change: (PlaybackAccountRecord) -> PlaybackAccountRecord): PlaybackResult {
        if (closed) return result(PlaybackCode.CLOSED)
        if (!available) return result(PlaybackCode.STORAGE_ERROR)
        val window = record.meter
        val code = try {
            // The store never owns the integration window; this session's own window is carried into the change.
            record = checked(store.update(accountId) { prior -> checked(change(checked(requireNotNull(prior)).withWindow(window))) })
            PlaybackCode.OK
        } catch (event: EventRejected) { event.code }
        catch (_: Exception) {
            // A commit response can be lost after durability. Fail closed now; reopening/retry
            // reloads the same unique ledger, rather than compensating by granting again.
            available = false
            PlaybackCode.STORAGE_ERROR
        }
        publish()
        return result(code)
    }

    private fun PlaybackAccountRecord.withWindow(from: MeterRecord) =
        copy(meter = this.meter.copy(lastElapsedMs = from.lastElapsedMs, lastSpeedKph = from.lastSpeedKph))
    private fun MeterRecord.withoutWindow() = copy(lastElapsedMs = null, lastSpeedKph = null)
    private fun result(code: PlaybackCode) = PlaybackResult(code, state)
    private fun publish() { val value = state; listeners.toList().forEach { runCatching { it(value) } } }
    private fun fail(code: PlaybackCode): Nothing = throw EventRejected(code)
    private class EventRejected(val code: PlaybackCode) : RuntimeException()

    companion object {
        const val WELCOME_TICKET = "t-welcome-01"
        const val TICKET_DURATION_MS = 15_000L
        /** A new install holds one welcome briefing ticket and no credits. */
        fun initialRecord(accountId: String) = PlaybackAccountRecord(accountId, ads = listOf(AdPlayback(WELCOME_TICKET, 0, TICKET_DURATION_MS)))
        fun ticketId(dayKey: String, sequence: Int) = "t-$dayKey-" + sequence.toString().padStart(2, '0')
    }
}
