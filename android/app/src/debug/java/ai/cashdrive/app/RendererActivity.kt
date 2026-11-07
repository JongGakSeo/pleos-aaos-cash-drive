package ai.cashdrive.app

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import ai.cashdrive.app.render.PreviewPlugin
import ai.cashdrive.core.render.PreviewSession
import ai.cashdrive.core.safety.SafetyReducer
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only isolated host. Demo safety controls never authorize this activity. */
class RendererActivity : FragmentActivity(), GodotHost {
    private val session = PreviewSession()
    private val reducer = SafetyReducer()
    private var gateway: CarSafetyGateway? = null
    private var fragment: GodotFragment? = null
    private var plugin: PreviewPlugin? = null
    private lateinit var surface: FrameLayout
    private lateinit var fallback: SkinArtView
    private lateinit var status: TextView
    private lateinit var start: Button
    private val controls = mutableListOf<Button>()
    private var resumed = false
    private var loopStarted = false
    private var enginePaused = false
    private var attaching = false
    private var skin = "horse"
    private var night = false
    private var speed = 60
    private var reduced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore a Fragment that could initialize a second engine before a measured first draw.
        super.onCreate(null)
        if (Application.getProcessName() != "$packageName:renderer" || engineClaimed.get()) {
            finish(); return
        }
        skin = intent.getStringExtra("skin").takeIf { it in setOf("horse", "mine", "gpu") } ?: "horse"
        night = intent.getBooleanExtra("night", false)
        reduced = !android.animation.ValueAnimator.areAnimatorsEnabled()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundColor(if (night) 0xFF20241F.toInt() else 0xFFF2F1EA.toInt())
        }
        status = TextView(this).apply { textSize = 24f; setTextColor(if (night) -1 else 0xFF17352B.toInt()) }
        root.addView(status)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("horse" to "말", "mine" to "광산", "gpu" to "GPU").forEach { (id, label) ->
                addControl(this, label) { skin = id; session.stop(); update() }
            }
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(0, 30, 60, 90).forEach { value -> addControl(this, "$value km/h 미리보기") { speed = value; session.stop(); update() } }
        })
        val actions = LinearLayout(this)
        start = addControl(actions, "미리보기 시작") { session.start(); update(); attachAfterDraw() }
        addControl(actions, "움직임 줄이기") { reduced = !reduced; session.stop(); update() }
        root.addView(actions)
        val stage = FrameLayout(this)
        surface = FrameLayout(this).apply { id = View.generateViewId(); visibility = View.INVISIBLE }
        fallback = SkinArtView(this)
        stage.addView(surface, FrameLayout.LayoutParams(-1, -1))
        stage.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "닫기"; textSize = 24f; minHeight = dp(64)
            setOnClickListener { finish() }
        })
        setContentView(root)
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r-l != or-ol || b-t != ob-ot) { session.stop(); update() }
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(dp(24)+bars.left, dp(16)+bars.top, dp(24)+bars.right, dp(16)+bars.bottom)
            } else {
                view.setPadding(dp(24)+insets.systemWindowInsetLeft, dp(16)+insets.systemWindowInsetTop,
                    dp(24)+insets.systemWindowInsetRight, dp(16)+insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
        update()
    }

    override fun onStart() {
        super.onStart()
        if (!::surface.isInitialized) return
        gateway = CarSafetyGateway(this, reducer) { snapshot -> session.setSafety(snapshot.state); update() }
        gateway?.start()
    }
    override fun onResume() {
        super.onResume()
        resumed = true; session.setVisible(true)
        if (::surface.isInitialized) surface.post { update() }
    }
    override fun onPause() {
        resumed = false; session.setVisible(false)
        if (::surface.isInitialized) update()
        super.onPause()
    }
    override fun onStop() { gateway?.stop(); gateway = null; super.onStop() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        session.stop(); update()
        super.onConfigurationChanged(newConfig)
    }
    override fun onDestroy() {
        plugin?.publish("", false)
        super.onDestroy()
        // This process owns only the renderer. Main activity/account/distance state lives elsewhere.
        if (Application.getProcessName() == "$packageName:renderer" && engineClaimed.get()) Process.killProcess(Process.myPid())
    }

    private fun update() {
        if (!::surface.isInitialized) return
        val state = session.snapshot()
        status.text = if (!state.mayStart) "안전 상태 확인 중 · 실제 UXR 비제한 + P가 필요합니다"
            else "Godot 실험 · $speed km/h 디자인 입력 · ${if (reduced) "움직임 줄이기" else "반복 미리보기"}"
        controls.forEach { it.isEnabled = state.mayStart }
        start.isEnabled = state.mayStart && !state.playing
        fallback.setSkin(skin); fallback.setNight(night)
        val visible = resumed && state.playing && loopStarted
        // Hide before publishing revocation: stale render callbacks cannot reveal an old frame.
        if (!visible) surface.visibility = View.INVISIBLE
        fallback.visibility = if (visible) View.GONE else View.VISIBLE
        val json = JSONObject().put("skinId", skin).put("theme", if (night) "night" else "day")
            .put("mode", if (state.playing) "PREVIEW" else "STATIC").put("previewSpeedKph", speed)
            .put("reducedMotion", reduced).put("generationId", state.generation)
            .put("viewport", JSONObject().put("width", maxOf(1, surface.width)).put("height", maxOf(1, surface.height)))
            .toString()
        plugin?.publish(json, resumed && state.playing)
        if (loopStarted) fragment?.let { host ->
            if (visible && enginePaused) { host.godot?.onResume(host); enginePaused = false }
            else if (!visible && !enginePaused) { host.godot?.onPause(host); enginePaused = true }
        }
        if (visible) surface.visibility = View.VISIBLE
    }

    private fun attachAfterDraw() {
        if (fragment != null || attaching) return
        attaching = true
        surface.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                surface.viewTreeObserver.removeOnPreDrawListener(this)
                surface.post {
                    attaching = false
                    if (!resumed || !session.snapshot().playing || isFinishing || supportFragmentManager.isStateSaved) return@post
                    if (!engineClaimed.compareAndSet(false, true)) return@post
                    fragment = GodotFragment()
                    supportFragmentManager.beginTransaction().replace(surface.id, fragment!!).commitNow()
                }
                return true
            }
        })
        surface.invalidate()
    }
    override fun getActivity(): Activity = this
    override fun getGodot(): Godot? = fragment?.godot
    override fun getCommandLine() = listOf("--main-pack", "res://cash_drive.pck", "--rendering-method", "gl_compatibility")
    override fun getHostPlugins(godot: Godot): Set<GodotPlugin> = setOf(PreviewPlugin(godot).also { plugin = it; update() })
    override fun onGodotMainLoopStarted() { runOnUiThread { loopStarted = true; enginePaused = false; update() } }
    override fun onGodotForceQuit(instance: Godot) { runOnUiThread { finish() } }
    override fun onGodotRestartRequested(instance: Godot) { runOnUiThread { finish() } }

    private fun addControl(row: LinearLayout, label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 24f; minHeight = dp(64); isAllCaps = false
        setOnClickListener { if (session.snapshot().mayStart) click() }
        row.addView(this, LinearLayout.LayoutParams(0, -2, 1f)); controls += this
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private companion object { val engineClaimed = AtomicBoolean(false) }
}
