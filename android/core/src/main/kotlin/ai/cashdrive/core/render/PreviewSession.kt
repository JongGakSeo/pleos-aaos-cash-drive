package ai.cashdrive.core.render

import ai.cashdrive.core.safety.SafetyState

data class PreviewSnapshot(
    val generation: Long,
    val mayStart: Boolean,
    val playing: Boolean,
)

/** Preview permission never survives a visibility, safety or viewport interruption. */
class PreviewSession {
    private var visible = false
    private var parked = false
    private var requested = false
    private var generation = 0L

    fun setVisible(value: Boolean): PreviewSnapshot {
        visible = value
        if (!value) requested = false
        return changed()
    }
    fun setSafety(state: SafetyState): PreviewSnapshot {
        parked = state == SafetyState.PARKED_ALLOWED
        if (!parked) requested = false
        return changed()
    }
    fun start(): PreviewSnapshot {
        requested = visible && parked
        return changed()
    }
    fun stop(): PreviewSnapshot {
        requested = false
        return changed()
    }
    fun snapshot() = PreviewSnapshot(generation, visible && parked, visible && parked && requested)
    private fun changed(): PreviewSnapshot { generation++; return snapshot() }
}
