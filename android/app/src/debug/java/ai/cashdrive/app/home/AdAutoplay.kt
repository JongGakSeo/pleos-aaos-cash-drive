package ai.cashdrive.app.home

import ai.cashdrive.core.rewards.TicketStatus
import ai.cashdrive.core.session.CashDriveSession
import ai.cashdrive.core.session.CashDriveState
import ai.cashdrive.core.session.SessionCode

/**
 * Plays ads one after another while the session allows it, credits each finished ad, and
 * remembers where an interrupted ad stopped so the next stop resumes from that position.
 *
 * The ad itself is a LOCAL_MOCK timer, not a provider SDK. Session contract v1 still needs an
 * explicit confirm, so this class confirms right after completion (user decision: no confirm step).
 * Not thread-safe: call every method from the main thread, and deliver session updates posted,
 * never synchronously from inside a session call.
 */
class AdAutoplay(
    private val session: CashDriveSession,
    private val positions: PositionStore,
    private val listener: Listener,
    val lengthMs: Long = DEFAULT_LENGTH_MS,
) {
    interface PositionStore {
        fun load(ticketId: String): Long
        fun save(ticketId: String, positionMs: Long)
        fun clear(ticketId: String)
    }

    interface Listener {
        /** null means no ad is on screen. */
        fun onProgress(progress: Progress?)
        fun onCredited(points: Int, remaining: Int)
        fun onInterrupted(positionMs: Long)
    }

    data class Progress(val ticketId: String, val index: Int, val total: Int, val positionMs: Long, val lengthMs: Long)

    private var current: String? = null
    private var positionMs = 0L
    private var finishedThisStop = 0
    private var allowed = false
    private var held = false
    private var busy = false

    val playing: Boolean get() = current != null

    /** Pauses playback while the user is in a menu or settings, without counting as an interruption. */
    fun hold(value: Boolean) {
        if (held == value) return
        held = value
        if (held) park(notify = false) else onState(session.state)
    }

    fun onState(state: CashDriveState) {
        if (busy) return
        val nowAllowed = state.actionsAllowed && !held
        if (!nowAllowed) {
            park(notify = allowed && current != null)
            allowed = false
            return
        }
        if (!allowed) finishedThisStop = 0
        allowed = true
        if (current == null) startNext(state) else publish(state)
    }

    fun tick(deltaMs: Long) {
        val id = current ?: return
        if (!allowed || deltaMs <= 0) return
        positionMs = minOf(lengthMs, positionMs + deltaMs)
        if (positionMs < lengthMs) {
            publish(session.state)
            return
        }
        finish(id)
    }

    private fun startNext(state: CashDriveState) {
        val ticket = state.tickets.firstOrNull { it.status == TicketStatus.AD_PLAYING }
            ?: state.tickets.firstOrNull { it.status == TicketStatus.PENDING }
        if (ticket == null) {
            listener.onProgress(null)
            return
        }
        if (ticket.status == TicketStatus.PENDING && call { session.startAd(ticket.id).code } != SessionCode.OK) {
            listener.onProgress(null)
            return
        }
        current = ticket.id
        positionMs = positions.load(ticket.id).coerceIn(0, lengthMs - 1)
        publish(session.state)
    }

    private fun finish(id: String) {
        current = null
        positions.clear(id)
        val points = session.state.tickets.firstOrNull { it.id == id }?.points ?: 0
        val completed = call { session.completeAd(id).code } == SessionCode.OK &&
            call { session.confirm(id).code } == SessionCode.OK
        if (completed) {
            finishedThisStop += 1
            listener.onCredited(points, session.state.remainingAdCount)
        }
        if (allowed) startNext(session.state) else listener.onProgress(null)
    }

    private fun park(notify: Boolean) {
        val id = current ?: return
        positions.save(id, positionMs)
        current = null
        listener.onProgress(null)
        if (notify) listener.onInterrupted(positionMs)
    }

    private fun publish(state: CashDriveState) {
        val id = current ?: return
        val total = finishedThisStop + 1 + state.remainingAdCount
        listener.onProgress(Progress(id, finishedThisStop + 1, total, positionMs, lengthMs))
    }

    private fun <T> call(block: () -> T): T {
        busy = true
        try { return block() } finally { busy = false }
    }

    companion object {
        const val DEFAULT_LENGTH_MS = 15_000L
    }
}
