package ai.cashdrive.app.home

import ai.cashdrive.core.session.ThemeIds
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.R
import ai.cashdrive.app.session.PlaybackSessionHost

/**
 * Launcher entry: asks for the speed permission in a task of its own, then opens [HomeActivity] in a fresh task.
 *
 * The Pleos launcher (HOME_SCREEN, emulator 2.1.0) remembers the activity on top of a hosted task at the moment
 * the task first gets its bounds (`TaskViewContainer::onFullscreenAvailable`) and never updates it. When the
 * home asked for the permission itself, the system dialog was on top at that moment, so the launcher kept the
 * task as `GrantPermissionsActivity` (not distraction optimized) and covered the app while driving
 * ("주행 중에는 사용할 수 없습니다") although HomeActivity is distraction optimized and the dialog was long gone
 * (observed 2026-11-08, planning/evidence/2026-11-08-launcher-gate). This activity has its own task affinity,
 * so the dialog only ever tops this throwaway task; the home task is created afterwards with the home on top.
 *
 * It renders the home's scene still (day/night) with the home's header over its sky and, on a card under the
 * header, one line on why the dialog talks about location, nothing else: it is on screen for a frame when
 * the permission is already granted, and behind the dialog otherwise. The manifest keeps the activity across
 * a night switch (uiMode is in its configChanges, so the pending dialog is never re-asked);
 * [onConfigurationChanged] rebuilds the view in the new look instead.
 */
class PermissionGateActivity : Activity() {
    private var shownNight: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showGate(resources.configuration)
        if (BuildConfig.DEMO_SIGNALS || granted()) { openHome(); return }
        // A recreated instance (a process restart while the dialog is up) still receives the pending result.
        if (savedInstanceState == null) requestPermissions(arrayOf(PlaybackSessionHost.SPEED_PERMISSION), REQUEST_SPEED)
    }

    /** A night switch while the dialog is up: the gate redraws in the new look (day or night tokens and still). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        showGate(newConfig)
    }

    private fun showGate(config: Configuration) {
        val night = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night == shownNight) return
        shownNight = night
        val tokens = if (night) PleosTokens.dark else PleosTokens.light
        window.decorView.setBackgroundColor(tokens.background)
        setContentView(GateView(this, tokens))
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Denied is also an answer: the home shows its lock with the "권한 허용" action, which comes back here.
        if (requestCode == REQUEST_SPEED) openHome()
    }

    private fun granted() = checkSelfPermission(PlaybackSessionHost.SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun openHome() {
        if (isFinishing) return
        // NEW_TASK + a different affinity: a new task when the home is not running, its existing task otherwise.
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_PERMISSION_ASKED, true))
        finishAndRemoveTask()
    }

    /**
     * Behind the system dialog: the home's backdrop still (day/night; the panorama on a landscape window, the
     * scene column on a portrait one, [Stills.backdrop]) standing on the bottom edge with its sky carried up to
     * the top ([SkyStill], as on the home's lock), the home header
     * (the app's mark, the title) over that sky at the top bar's place, and right under it the one-line
     * explanation on a card. The system dialog takes the middle band, so the note stays above it and the horse
     * stays whole on the road below it.
     */
    private class GateView(context: Context, private val tokens: PleosTokens) : FrameLayout(context) {
        private val still = SkyStill(context)
        private var stillWide: Boolean? = null

        init {
            val kit = PleosKit(context)
            setBackgroundColor(tokens.background)
            addView(still, LayoutParams(-1, -1))
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(kit.dp(32), 0, kit.dp(32), 0)
            }
            header.addView(kit.brandMark(48), LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)))
            header.addView(kit.text("킬로모아", 32, tokens.text, bold = true).apply { letterSpacing = -0.01f },
                LinearLayout.LayoutParams(-2, -2).apply { leftMargin = kit.dp(16) })
            addView(header, LayoutParams(-1, kit.dp(HomeView.NAV), Gravity.TOP))
            val why = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = kit.rounded(tokens.surface, 20f, 1, tokens.line)
                elevation = kit.dp(8).toFloat()
                setPadding(kit.dp(24), kit.dp(16), kit.dp(32), kit.dp(16))
            }
            why.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_speed); imageTintList = ColorStateList.valueOf(tokens.text)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(kit.dp(28), kit.dp(28)).apply { rightMargin = kit.dp(16) })
            why.addView(kit.text("주행 거리는 차량 속도 신호로 계산해요. 위치는 읽지 않아요.", 24, tokens.text).apply { maxLines = 2 })
            // Aligned with the header's mark, 8dp under the top bar's band.
            addView(why, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(kit.dp(32), kit.dp(HomeView.NAV + 8), kit.dp(32), 0)
            })
            setOnApplyWindowInsetsListener { root, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }

        /** The still follows the window's shape, as on the home's lock: the panorama for a landscape window. */
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val wide = w >= h
            if (wide == stillWide) return
            stillWide = wide
            // The gate comes before the session opens (a first launch): the default theme's still.
            still.show(Stills.backdrop(ThemeIds.DEFAULT, tokens.night, landscape = wide))
        }
    }

    companion object {
        /** Set on the home intent so the home does not ask a second time in the same launch. */
        const val EXTRA_PERMISSION_ASKED = "ai.cashdrive.app.home.PERMISSION_ASKED"
        private const val REQUEST_SPEED = 41

        fun intent(context: Context): Intent = Intent(context, PermissionGateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
