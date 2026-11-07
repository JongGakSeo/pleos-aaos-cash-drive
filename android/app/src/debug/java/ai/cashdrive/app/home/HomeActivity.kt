package ai.cashdrive.app.home

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.MainActivity
import ai.cashdrive.app.render.PreviewPlugin
import ai.cashdrive.app.session.DemoSessionHost
import ai.cashdrive.core.safety.SafetyState
import ai.cashdrive.core.session.CashDriveState
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Demo home: Godot theme scene with Pleos-style native panels on top. Debug-only because the
 * Godot engine and the LOCAL_MOCK session are debug dependencies. Runs in its own process so the
 * engine initializes once and the process ends with the screen.
 */
class HomeActivity : FragmentActivity(), GodotHost, HomeView.Actions, AdAutoplay.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var view: HomeView
    private lateinit var host: DemoSessionHost
    private lateinit var autoplay: AdAutoplay
    private var subscription: AutoCloseable? = null
    private var state: CashDriveState? = null
    private var adProgress: AdAutoplay.Progress? = null
    private var fragment: GodotFragment? = null
    private var plugin: PreviewPlugin? = null
    private var generation = 0L
    private var resumed = false
    private var loopStarted = false
    private var enginePaused = false
    private var attaching = false
    private var lastTick = 0L
    private val prefs by lazy { getSharedPreferences("cash_drive_home_v1", MODE_PRIVATE) }
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            autoplay.tick(now - lastTick)
            lastTick = now
            if (resumed && autoplay.playing) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore a fragment that could start a second engine in this process.
        super.onCreate(null)
        if (Application.getProcessName() != "$packageName:home" || engineClaimed.get()) {
            finish(); return
        }
        view = HomeView(this, this)
        view.setLock("차량 상태를 확인하고 있어요", "확인되면 바로 이어서 보여 드려요")
        setContentView(view.root)
        host = DemoSessionHost(this, ACCOUNT)
        autoplay = AdAutoplay(host.ui, PrefsPositions(), this)
        if (BuildConfig.DEMO_SIGNALS) addDemoChip()
        view.root.setOnApplyWindowInsetsListener { root, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.sceneHost.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) publishScene()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.menuOpen || view.settingsOpen) onCloseOverlay() else moveTaskToBack(true)
            }
        })
        applyLook()
    }

    override fun onStart() {
        super.onStart()
        if (!::host.isInitialized) return
        host.start()
        subscription = host.ui.observe { snapshot -> main.post { onState(snapshot) } }
    }

    override fun onResume() {
        super.onResume()
        if (!::host.isInitialized) return
        resumed = true
        autoplay.hold(view.menuOpen || view.settingsOpen)
        state?.let { autoplay.onState(it) }
        startTicker()
        attachAfterDraw()
        publishScene()
    }

    override fun onPause() {
        if (::host.isInitialized) {
            resumed = false
            autoplay.hold(true)
            main.removeCallbacks(ticker)
            publishScene()
        }
        super.onPause()
    }

    override fun onStop() {
        subscription?.close(); subscription = null
        if (::host.isInitialized) host.stop()
        super.onStop()
    }

    override fun onDestroy() {
        plugin?.publish("", false)
        super.onDestroy()
        if (Application.getProcessName() == "$packageName:home" && engineClaimed.get()) Process.killProcess(Process.myPid())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::view.isInitialized) { applyLook(); publishScene() }
    }

    /** Libraries may lock orientation; Pleos panels must stay resizable in both directions. */
    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    private fun onState(snapshot: CashDriveState) {
        state = snapshot
        if (!snapshot.actionsAllowed && (view.menuOpen || view.settingsOpen)) view.closeOverlay()
        autoplay.hold(!resumed || view.menuOpen || view.settingsOpen)
        autoplay.onState(snapshot)
        startTicker()
        render()
    }

    private fun render() {
        val current = state ?: return
        view.setTheme(current.themeId, night)
        view.setFigures(current.confirmedPoints, current.tripCandidatePoints, current.remainingAdCount)
        val label = when (current.safetyState) {
            SafetyState.PARKED_ALLOWED, SafetyState.STOPPED_UNQUALIFIED -> VehicleLabel.STOPPED
            SafetyState.RESTRICTED -> VehicleLabel.DRIVING
            SafetyState.UNKNOWN -> VehicleLabel.CHECKING
        }
        view.setVehicle(label, current.actionsAllowed, adProgress != null)
        view.setAd(adProgress, sound())
        publishScene()
    }

    private fun applyLook() {
        view.setTokens(if (night) PleosTokens.dark else PleosTokens.light)
        window.decorView.setBackgroundColor(if (night) PleosTokens.dark.background else PleosTokens.light.background)
        render()
    }

    // Scene: motion only while the scene is on screen and the vehicle state allows it.
    private fun publishScene() {
        val current = state
        val safety = current?.safetyState ?: SafetyState.UNKNOWN
        val sceneShown = resumed && safety != SafetyState.UNKNOWN && adProgress == null
        val motionBySafety = when (safety) {
            SafetyState.PARKED_ALLOWED, SafetyState.STOPPED_UNQUALIFIED -> true
            // User decision 2026-11-07: demo builds keep moving while driving; vehicle stays still.
            SafetyState.RESTRICTED -> BuildConfig.FLAVOR != "vehicle"
            SafetyState.UNKNOWN -> false
        }
        val motion = sceneShown && motionBySafety && !reduceMotion() && !view.menuOpen && !view.settingsOpen
        generation += 1
        val json = JSONObject()
            .put("skinId", current?.themeId ?: "horse")
            .put("mode", if (motion) "PREVIEW" else "STATIC")
            .put("theme", if (night) "night" else "day")
            .put("reducedMotion", reduceMotion())
            .put("speedKph", 0)
            .put("generationId", generation)
            .put("viewport", JSONObject().put("width", maxOf(1, view.sceneHost.width)).put("height", maxOf(1, view.sceneHost.height)))
            .toString()
        plugin?.publish(json, motion)
        if (loopStarted) fragment?.let { godotHost ->
            if (sceneShown && enginePaused) { godotHost.godot?.onResume(godotHost); enginePaused = false }
            else if (!sceneShown && !enginePaused) { godotHost.godot?.onPause(godotHost); enginePaused = true }
        }
        view.sceneHost.visibility = if (loopStarted && sceneShown) View.VISIBLE else View.INVISIBLE
    }

    private fun attachAfterDraw() {
        if (fragment != null || attaching) return
        attaching = true
        view.sceneHost.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.sceneHost.viewTreeObserver.removeOnPreDrawListener(this)
                // Create the surface after the window has drawn once (Pleos 14 relayout timeout).
                view.sceneHost.post {
                    attaching = false
                    if (!resumed || isFinishing || supportFragmentManager.isStateSaved) return@post
                    if (!engineClaimed.compareAndSet(false, true)) return@post
                    fragment = GodotFragment()
                    supportFragmentManager.beginTransaction().replace(view.sceneHost.id, fragment!!).commitNow()
                }
                return true
            }
        })
        view.sceneHost.invalidate()
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        if (resumed && autoplay.playing) { lastTick = SystemClock.uptimeMillis(); main.postDelayed(ticker, TICK_MS) }
    }

    // AdAutoplay.Listener
    override fun onProgress(progress: AdAutoplay.Progress?) {
        val changed = (progress == null) != (adProgress == null)
        adProgress = progress
        view.setAd(progress, sound())
        if (changed) render()
    }

    override fun onCredited(points: Int, remaining: Int) {
        view.showToast(if (remaining > 0) "광고 1편을 다 봐서 ${points} P가 적립됐어요" else "광고를 모두 봤어요. ${points} P가 적립됐어요")
    }

    override fun onInterrupted(positionMs: Long) {
        view.showToast("출발해서 광고를 멈췄어요. 다음에 정차하면 0:%02d부터 이어서 재생돼요".format(positionMs / 1000))
    }

    // HomeView.Actions
    override fun onThemeMenu() {
        val current = state ?: return
        if (!current.actionsAllowed) return
        if (view.menuOpen) { onCloseOverlay(); return }
        view.openThemeMenu(current.themeId, night)
        autoplay.hold(true); publishScene()
    }

    override fun onThemeSelected(id: String) {
        host.ui.selectTheme(id)
        onCloseOverlay()
    }

    override fun onSettings() {
        if (state?.actionsAllowed != true) return
        view.openSettings(sound(), reduceMotion())
        autoplay.hold(true); publishScene()
    }

    override fun onCloseOverlay() {
        view.closeOverlay()
        autoplay.hold(!resumed)
        startTicker()
        render()
    }

    override fun onSoundToggle() {
        prefs.edit().putBoolean("sound", !sound()).apply()
        if (view.settingsOpen) view.openSettings(sound(), reduceMotion())
        view.setAd(adProgress, sound())
    }

    override fun onReduceMotionToggle() {
        prefs.edit().putBoolean("reduceMotion", !prefs.getBoolean("reduceMotion", false)).apply()
        view.openSettings(sound(), reduceMotion())
        publishScene()
    }

    override fun demoTools(): List<Pair<String, () -> Unit>> = emptyList()

    /** Ad sound preference. The LOCAL_MOCK ad has no audio yet, so no audio focus is requested. */
    private fun sound() = prefs.getBoolean("sound", true)
    private fun reduceMotion() = prefs.getBoolean("reduceMotion", false) || !android.animation.ValueAnimator.areAnimatorsEnabled()

    /** Demo flavor only: synthetic vehicle states and distance for presentations. Never in emu/vehicle. */
    private fun addDemoChip() {
        val chip = TextView(this).apply {
            text = "시연"; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC313236.toInt()); cornerRadius = 999f }
            setPadding(view.dp(20), view.dp(12), view.dp(20), view.dp(12))
            contentDescription = "시연 도구"
            setOnClickListener { showDemoTools() }
        }
        view.root.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(view.dp(16), 0, 0, view.dp(HomeView.DOCK + 16))
        })
    }

    private fun showDemoTools() {
        val tools = listOf<Pair<String, () -> Unit>>(
            "주차" to { host.updateMockSafety(SafetyState.PARKED_ALLOWED) },
            "신호 대기" to { host.updateMockSafety(SafetyState.STOPPED_UNQUALIFIED) },
            "주행" to { host.updateMockSafety(SafetyState.RESTRICTED) },
            "상태 미확인" to { host.updateMockSafety(SafetyState.UNKNOWN) },
            "주행 거리 2 km 추가 (주차 중)" to {
                val stamp = System.currentTimeMillis()
                host.addMockDistance("demo-$stamp-1"); host.addMockDistance("demo-$stamp-2")
            },
            "개발 화면 열기" to { startActivity(Intent(this, MainActivity::class.java)) },
        )
        AlertDialog.Builder(this).setTitle("시연 도구").setItems(tools.map { it.first }.toTypedArray()) { _, which ->
            tools[which].second()
        }.show()
    }

    private inner class PrefsPositions : AdAutoplay.PositionStore {
        override fun load(ticketId: String) = prefs.getLong("adpos:$ticketId", 0L)
        override fun save(ticketId: String, positionMs: Long) { prefs.edit().putLong("adpos:$ticketId", positionMs).apply() }
        override fun clear(ticketId: String) { prefs.edit().remove("adpos:$ticketId").apply() }
    }

    // GodotHost
    override fun getActivity(): Activity = this
    override fun getGodot(): Godot? = fragment?.godot
    override fun getCommandLine() = listOf("--main-pack", "res://cash_drive.pck", "--rendering-method", "gl_compatibility")
    override fun getHostPlugins(godot: Godot): Set<GodotPlugin> = setOf(PreviewPlugin(godot).also { plugin = it })
    override fun onGodotMainLoopStarted() { runOnUiThread { loopStarted = true; enginePaused = false; publishScene() } }
    override fun onGodotForceQuit(instance: Godot) { runOnUiThread { finish() } }
    override fun onGodotRestartRequested(instance: Godot) { runOnUiThread { finish() } }

    private companion object {
        const val ACCOUNT = "demo-home"
        const val TICK_MS = 100L
        val engineClaimed = AtomicBoolean(false)
    }
}
