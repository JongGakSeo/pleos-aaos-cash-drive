package ai.cashdrive.core.playback

import ai.cashdrive.core.session.ThemeIds

/** Local mock ledger; not ad supply, provider verification, payment, or server balance. */
class MockPlaybackSession(private val accountId: String, private val store: PlaybackAccountStore) : PlaybackSession, AutoCloseable {
    private var vehicle = VehicleStatus()
    private var available = true
    private var closed = false
    private val listeners = linkedSetOf<(PlaybackState) -> Unit>()
    private var record = PlaybackAccountRecord(accountId)

    init {
        require(accountId.isNotBlank())
        try { record = checked(store.update(accountId) { it ?: initialRecord(accountId) }) }
        catch (_: Exception) { available = false }
    }

    @get:Synchronized override val state: PlaybackState get() {
        val credited = record.ledger.map { it.adId }.toSet()
        val pending = record.ads.filter { it.adId !in credited }
        return PlaybackState(accountId, vehicle, record.totalPoints, record.tripCandidatePoints,
            pending.size, pending.firstOrNull(), ThemeIds.normalize(record.themeId), available)
    }

    @Synchronized override fun observe(listener: (PlaybackState) -> Unit): AutoCloseable {
        check(!closed)
        listeners += listener
        listener(state)
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    @Synchronized fun updateVehicle(status: VehicleStatus) {
        if (closed) return
        vehicle = status
        publish()
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
    @Synchronized fun enqueueMockAd(adId: String, durationMs: Long = 15_000): PlaybackResult = transact { current ->
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

    private fun checked(value: PlaybackAccountRecord): PlaybackAccountRecord {
        require(value.accountId == accountId)
        return value.validate()
    }

    private fun transact(change: (PlaybackAccountRecord) -> PlaybackAccountRecord): PlaybackResult {
        if (closed) return result(PlaybackCode.CLOSED)
        if (!available) return result(PlaybackCode.STORAGE_ERROR)
        val code = try {
            record = checked(store.update(accountId) { prior -> checked(change(checked(requireNotNull(prior)))) })
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

    private fun result(code: PlaybackCode) = PlaybackResult(code, state)
    private fun publish() { val value = state; listeners.toList().forEach { runCatching { it(value) } } }
    private fun fail(code: PlaybackCode): Nothing = throw EventRejected(code)
    private class EventRejected(val code: PlaybackCode) : RuntimeException()

    companion object {
        fun initialRecord(accountId: String) = PlaybackAccountRecord(accountId,
            ads = (1..3).map { AdPlayback("mock-initial-$it", 0, 15_000) }, tripCandidatePoints = 30)
    }
}
