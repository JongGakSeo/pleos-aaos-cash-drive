package ai.cashdrive.app.render

import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Renderer-only bridge: immutable latest frame; no credentials, rewards or vehicle speed. */
class PreviewPlugin(godot: Godot) : GodotPlugin(godot) {
    private val allowed = AtomicBoolean(false)
    private val frame = AtomicReference("")
    private val ready = AtomicBoolean(false)
    override fun getPluginName() = "CashDrivePreview"
    override fun getPluginSignals() = setOf(SignalInfo("frame_changed"))
    @UsedByGodot fun get_request_json(): String = frame.get()
    @UsedByGodot fun is_allowed(): Boolean = allowed.get()
    fun publish(json: String, previewAllowed: Boolean) {
        // Revoke before publishing a new frame; native also hides the surface immediately.
        if (!previewAllowed) allowed.set(false)
        frame.set(json)
        if (previewAllowed) allowed.set(true)
        if (ready.get()) emitSignal("frame_changed")
    }
    override fun onGodotMainLoopStarted() { ready.set(true); emitSignal("frame_changed") }
    override fun onGodotTerminating() { allowed.set(false); ready.set(false) }
}
