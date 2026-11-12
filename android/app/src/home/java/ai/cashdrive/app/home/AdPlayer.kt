package ai.cashdrive.app.home

import ai.cashdrive.core.playback.PlaybackCode
import ai.cashdrive.core.playback.PlaybackSession
import ai.cashdrive.core.playback.PlaybackState

/**
 * LOCAL_MOCK ad player for session contract v2. The session decides whether playback is allowed,
 * which ad is current and where it resumes; this class only advances a timer while it is allowed,
 * reports the real position, and reports completion once. Main thread only. Session updates must be
 * delivered posted, never synchronously from inside a session call.
 */
class AdPlayer(private val session: PlaybackSession, private val listener: Listener) {
    interface Listener {
        /** null means no ad is on screen. */
        fun onProgress(progress: Progress?)
        fun onCredited(points: Int, remaining: Int)
        fun onInterrupted(positionMs: Long)
    }

    data class Progress(val adId: String, val index: Int, val total: Int, val positionMs: Long, val durationMs: Long)

    private var adId: String? = null
    private var positionMs = 0L
    private var durationMs = 1L
    private var sinceSaveMs = 0L
    private var creditedThisStop = 0
    private var allowed = false
    private var held = false
    private var busy = false

    val playing: Boolean get() = adId != null

    /** Pauses for menus, settings or background without counting as a vehicle interruption. */
    fun hold(value: Boolean) {
        if (held == value) return
        held = value
        if (held) stop(notify = false) else onState(session.state)
    }

    fun onState(state: PlaybackState) {
        if (busy) return
        val nowAllowed = state.playbackAllowed && !held
        val ad = state.currentAd
        if (!nowAllowed || ad == null) {
            stop(notify = allowed && !state.playbackAllowed && adId != null)
            if (!state.playbackAllowed) creditedThisStop = 0
            allowed = nowAllowed
            if (ad == null || !nowAllowed) listener.onProgress(null)
            return
        }
        allowed = true
        if (adId != ad.adId) {
            adId = ad.adId
            // A late position from this player may be ahead of the stored one; never go backwards.
            positionMs = ad.positionMs.coerceIn(0, ad.durationMs)
            durationMs = ad.durationMs.coerceAtLeast(1)
            sinceSaveMs = 0
        }
        publish(state)
    }

    fun tick(deltaMs: Long) {
        val id = adId ?: return
        if (!allowed || held || deltaMs <= 0) return
        positionMs = minOf(durationMs, positionMs + deltaMs)
        sinceSaveMs += deltaMs
        if (positionMs >= durationMs) {
            complete(id)
            return
        }
        if (sinceSaveMs >= SAVE_EVERY_MS) save(id)
        publish(session.state)
    }

    private fun complete(id: String) {
        val before = session.state.totalPoints
        adId = null
        val result = call { session.onAdCompleted(id) }
        if (result.code == PlaybackCode.OK) {
            val gained = result.state.totalPoints - before
            if (gained > 0) {
                creditedThisStop += 1
                listener.onCredited(gained, result.state.remainingAdCount)
            }
        }
        onState(session.state)
    }

    private fun stop(notify: Boolean) {
        val id = adId ?: return
        save(id)
        adId = null
        listener.onProgress(null)
        if (notify) listener.onInterrupted(positionMs)
    }

    private fun save(id: String) {
        sinceSaveMs = 0
        call { session.onPlaybackPosition(id, positionMs) }
    }

    private fun publish(state: PlaybackState) {
        val id = adId ?: return
        val total = creditedThisStop + state.remainingAdCount
        listener.onProgress(Progress(id, creditedThisStop + 1, maxOf(total, creditedThisStop + 1), positionMs, durationMs))
    }

    private fun <T> call(block: () -> T): T {
        busy = true
        try { return block() } finally { busy = false }
    }

    private companion object { const val SAVE_EVERY_MS = 1_000L }
}
