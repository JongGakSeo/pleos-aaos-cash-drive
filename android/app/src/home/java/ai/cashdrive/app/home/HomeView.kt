package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.cashdrive.app.R

/**
 * Pleos Connect grayscale tokens (basic_00..900) plus the one accent. Light for day, dark for night.
 * [accent] is amber for the credits figures outside the meter (the top bar's chip, the credits page); the
 * meter card keeps its own dark face and amber in both looks ([MeterFace]), and so do the toasts.
 * The light amber is #9C6E1A (about 4.5:1 on the white panel, spec 6); the dark one stays #E0A93C.
 * The toast is #313236 with white text in both looks (design §8): a light toast would glare at night.
 * Changing a colour here is the only place that changes it: every view reads the tokens.
 */
data class PleosTokens(
    val background: Int, val surface: Int, val popup: Int, val text: Int, val subText: Int,
    val line: Int, val filled: Int, val onFilled: Int, val soft: Int, val toast: Int, val onToast: Int,
    val accent: Int,
    val night: Boolean,
) {
    companion object {
        val light = PleosTokens(0xFFF7F8FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF131417.toInt(),
            0xFF686A72.toInt(), 0xFFE0E1E6.toInt(), 0xFF44464E.toInt(), 0xFFFFFFFF.toInt(), 0x0F131417,
            0xFF313236.toInt(), 0xFFFFFFFF.toInt(), 0xFF9C6E1A.toInt(), night = false)
        val dark = PleosTokens(0xFF1D1E21.toInt(), 0xFF131417.toInt(), 0xFF313236.toInt(), 0xFFFFFFFF.toInt(),
            0xFFC7C9CE.toInt(), 0xFF44464E.toInt(), 0xFFE0E1E6.toInt(), 0xFF131417.toInt(), 0x14FFFFFF,
            0xFF313236.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0A93C.toInt(), night = true)
    }
}

/**
 * The scene stills (godot/tools/render_stills.gd), day or night:
 * - [scene] stands behind the engine surface while it is hidden or starting. It is rendered at the scene
 *   area's own framing (the column beside the meter, 1010x1122, or the band above it, 840x702) and shown
 *   fitted to the area's width on its bottom edge, so it lines up with the live scene: nothing jumps when
 *   the surface comes or goes.
 * - [backdrop] stands behind the lock and the permission gate: the 1682x800 panorama on a landscape
 *   window, the column still on a portrait one.
 */
object Stills {
    /** A scene area this much wider than tall is the narrow panel's band (ThemeStage.BAND_ASPECT). */
    const val BAND_ASPECT = 1.08f

    /** Stills per theme (core ThemeIds): day and night, each as wide, narrow, panorama and the settings thumbnail. */
    private val SETS: Map<String, IntArray> = mapOf(
        "horse" to intArrayOf(
            R.drawable.scene_horse_day_wide, R.drawable.scene_horse_day_narrow, R.drawable.scene_horse_day_panorama, R.drawable.scene_horse_day_thumb,
            R.drawable.scene_horse_night_wide, R.drawable.scene_horse_night_narrow, R.drawable.scene_horse_night_panorama, R.drawable.scene_horse_night_thumb),
        "mine" to intArrayOf(
            R.drawable.scene_mine_day_wide, R.drawable.scene_mine_day_narrow, R.drawable.scene_mine_day_panorama, R.drawable.scene_mine_day_thumb,
            R.drawable.scene_mine_night_wide, R.drawable.scene_mine_night_narrow, R.drawable.scene_mine_night_panorama, R.drawable.scene_mine_night_thumb),
        "mining" to intArrayOf(
            R.drawable.scene_mining_day_wide, R.drawable.scene_mining_day_narrow, R.drawable.scene_mining_day_panorama, R.drawable.scene_mining_day_thumb,
            R.drawable.scene_mining_night_wide, R.drawable.scene_mining_night_narrow, R.drawable.scene_mining_night_panorama, R.drawable.scene_mining_night_thumb),
    )
    private const val WIDE = 0
    private const val NARROW = 1
    private const val PANORAMA = 2
    private const val THUMB = 3

    /** An unknown theme shows the horse, like the session's own fallback (ThemeIds.normalize). */
    private fun pick(skin: String, night: Boolean, shape: Int): Int = (SETS[skin] ?: SETS.getValue("horse"))[(if (night) 4 else 0) + shape]

    fun scene(skin: String, night: Boolean, width: Int, height: Int): Int =
        pick(skin, night, if (height > 0 && width >= height * BAND_ASPECT) NARROW else WIDE)

    fun backdrop(skin: String, night: Boolean, landscape: Boolean): Int = pick(skin, night, if (landscape) PANORAMA else WIDE)

    fun thumb(skin: String, night: Boolean): Int = pick(skin, night, THUMB)
}

/**
 * A scene still that fills its width and stands on its bottom edge like the stage it was rendered from; the
 * height left above it continues the still's sky in the colour of its top row. A centre crop would blow the
 * horse up and cut it; this keeps the whole horse on the road at the bottom. Behind the engine it lines up
 * with the live scene; on the lock and the gate it keeps the horse under the cards and the system dialog.
 */
@SuppressLint("ViewConstructor")
internal class SkyStill(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.MATRIX
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(picture: Int) {
        setImageResource(picture)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val picture = drawable ?: return
        if (width <= 0 || height <= 0 || picture.intrinsicWidth <= 0) return
        val scale = width.toFloat() / picture.intrinsicWidth
        imageMatrix = Matrix().apply { setScale(scale, scale); postTranslate(0f, height - picture.intrinsicHeight * scale) }
        val bitmap = (picture as? BitmapDrawable)?.bitmap
        if (bitmap != null && bitmap.config != Bitmap.Config.HARDWARE) setBackgroundColor(bitmap.getPixel(bitmap.width / 2, 0))
    }
}

/**
 * Status dot colours follow the dashboard convention: green filled in park, green outline while waiting
 * at a light (D at 0 km/h, never playable), orange driving, grey outline while checking.
 */
enum class VehicleLabel(val text: String) { PARKED("정차 중"), WAITING("신호 대기"), DRIVING("주행 중"), CHECKING("확인 중") }

/**
 * Builds the Pleos-style home as a dashboard: top bar, scene host, the taxi-meter panel ([MeterPanel]:
 * odometer drums, ticket gauge, stub row, credits, context and hint lines) with the parked actions under
 * it, briefing player, the reward sequence ([RewardSequence]: arrival and welcome cards, the completion
 * burst), credits page, settings page, toast and lock screen. Holds no reward or safety logic: the host
 * decides every value and when it may be shown or move.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class HomeView(private val context: Context, private val actions: Actions) {
    interface Actions {
        fun onCredits()
        fun onSettings()
        fun onAbout()
        fun onCloseOverlay()
        fun onMotionWhileDrivingToggle()
        fun onReduceMotionToggle()
        fun onThemeSelected(id: String)
        fun onRedemptionSelected(id: String)
        fun demoTools(): List<Pair<String, () -> Unit>>
    }

    private val kit = PleosKit(context)
    private var tokens = PleosTokens.light
    private var wide = false
    private var label = VehicleLabel.CHECKING
    private var menusAllowed = false
    val root = FrameLayout(context)
    val sceneHost = FrameLayout(context).apply { id = View.generateViewId() }
    private val scene = FrameLayout(context)
    private val still = SkyStill(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var stillKey: List<Any>? = null
    private var skin = "horse"
    private var sceneLive = false
    private var sceneWidth = 0
    private var sceneHeight = 0
    private val nav = LinearLayout(context)
    private val brand = kit.brandMark(48)
    private val title = TextView(context)
    private val dot = View(context)
    private val status = TextView(context)
    private val navChips = LinearLayout(context)
    private val navChipViews = mutableListOf<TextView>()
    private val creditsButton = LinearLayout(context)
    private val creditsNumber = TextView(context)
    private val creditsUnit = TextView(context)
    private var chipCredits = -1
    private var chipShown = 0
    private var chipAnimator: ValueAnimator? = null
    private val settingsButton = kit.iconButton(R.drawable.ic_home_gear, "설정") { actions.onSettings() }
    /** "소개": opens the page about how the scene was made ([AboutPage]). Shown with the other menus, in park. */
    private val aboutButton = TextView(context)
    private val panel = LinearLayout(context)
    private val panelContent = LinearLayout(context)
    private val meter = MeterPanel(context, kit)
    private var meterModel: MeterModel? = null
    private var motionLevel = MotionLevel.FULL
    private var hintText: String? = null
    private var contextText: String? = null
    private val actionsBox = LinearLayout(context)
    private var actionLabels: List<String> = emptyList()
    private var actionRuns: List<() -> Unit> = emptyList()
    private val player = BriefingPlayerView(context, kit)
    private val overlay = FrameLayout(context)
    private val toast = LinearLayout(context)
    private val toastSurface = ToastSurface(kit.dp(20).toFloat(), kit.dp(4).toFloat(), 1f)
    private val toastIcon = ImageView(context)
    private val toastText = TextView(context)
    /** A toast asked for while a briefing was on screen (wide): shown when the theater closes. */
    private var toastAfterBriefing: Triple<String, Boolean, Int?>? = null
    /** The ticket the theater shows, to tell the next briefing following straight on from the same one. */
    private var briefingTicket: String? = null
    private var toastReward = false
    private var toastLeaving = false
    private val lock = FrameLayout(context)
    private val lockStill = SkyStill(context)
    private val lockColumn = LinearLayout(context)
    private val lockCard = LinearLayout(context)
    private val lockSpinner = Spinner(context)
    private val lockTitle = TextView(context)
    private val lockBody = TextView(context)
    private val lockButton = TextView(context)
    private var hideToast: Runnable? = null
    /** Above the panel and the theater, below the pages, the lock and the toast: the reward cards and glyphs. */
    private val rewardHost = FrameLayout(context)
    private val rewards = RewardSequence(rewardHost, kit, tokens)
    private var creditsPage: CreditsPage? = null
    private var settingsPage: SettingsPage? = null
    private var aboutPage: AboutPage? = null
    /** The look the open page was built with: a new look rebuilds it, anything else updates it in place. */
    private var pageTokens: PleosTokens? = null

    /** The credits page is open (the historical name the host uses for its overlay checks). */
    var menuOpen = false; private set
    var settingsOpen = false; private set
    var aboutOpen = false; private set
    /** Any page lies over the home. */
    val pageOpen get() = menuOpen || settingsOpen || aboutOpen

    init {
        // The still lies over the engine's surface and leaves only once the engine has had time to draw: a
        // surface that just appeared shows its clear colour for a frame or two before the first scene frame.
        scene.addView(sceneHost, match())
        scene.addView(still, match())
        sceneHost.visibility = View.INVISIBLE
        root.addView(scene)
        root.addView(player); player.visibility = View.GONE
        buildNav(); root.addView(nav)
        buildPanel(); root.addView(panel)
        // Above the scene surface, not inside it: the engine's SurfaceView must never hide the cards. Above the
        // panel too, so the completion burst rises over the meter's credits figure.
        root.addView(rewardHost, match())
        rewards.onTicketsStamped = { meter.blinkStubs() }
        root.addView(overlay, match()); overlay.visibility = View.GONE
        buildLock(); root.addView(lock); lock.visibility = View.GONE
        buildToast(); root.addView(toast)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) layout(r - l, b - t)
        }
        setTokens(tokens)
    }

    fun setTokens(value: PleosTokens) {
        tokens = value
        root.setBackgroundColor(tokens.background)
        nav.setBackgroundColor(tokens.background)
        title.setTextColor(tokens.text); status.setTextColor(tokens.subText)
        // The top bar's buttons and the parked actions are Pleos filled buttons (2026-11-11; Basic Button,
        // Secondary: button_filled_enabled = basic_600 with the opposite label, #44464E and white by day,
        // #E0E1E6 and #131417 at night) instead of the 5% grey fill, which looked washed out beside the meter.
        // The credit figure takes the label colour: amber on this fill falls under the guide's 4.5:1 contrast.
        navChipViews.forEach { it.background = kit.rounded(tokens.filled, 24f); it.setTextColor(tokens.onFilled) }
        creditsButton.background = kit.rounded(tokens.filled, 28f)
        aboutButton.background = kit.rounded(tokens.filled, 28f); aboutButton.setTextColor(tokens.onFilled)
        creditsNumber.setTextColor(tokens.onFilled); creditsUnit.setTextColor(tokens.onFilled)
        kit.tint(settingsButton, tokens.onFilled, tokens.filled)
        // The panel column is the dashboard background; the dark meter face sits in it like an instrument.
        panel.setBackgroundColor(tokens.background)
        meter.setTokens(tokens)
        for (i in 0 until actionsBox.childCount) styleAction(actionsBox.getChildAt(i) as TextView)
        player.setTokens(tokens)
        rewards.setTokens(tokens)
        styleToast()
        lock.setBackgroundColor(tokens.background)
        lockCard.background = kit.rounded(tokens.surface, 20f, 1, tokens.line)
        lockTitle.setTextColor(tokens.text); lockBody.setTextColor(tokens.subText)
        lockSpinner.colors(tokens.text, tokens.line)
        lockButton.background = kit.rounded(tokens.filled, 14f); lockButton.setTextColor(tokens.onFilled)
        setVehicle(label, menusAllowed, status.text.endsWith(PLAYING_SUFFIX))
        updateStill()
    }

    fun setVehicle(label: VehicleLabel, menusAllowed: Boolean, playing: Boolean) {
        this.label = label
        this.menusAllowed = menusAllowed
        status.text = label.text + if (playing) PLAYING_SUFFIX else ""
        dot.background = when (label) {
            VehicleLabel.PARKED -> kit.oval(GREEN, 0)
            VehicleLabel.WAITING -> kit.oval(Color.TRANSPARENT, GREEN)
            VehicleLabel.DRIVING -> kit.oval(ORANGE, 0)
            VehicleLabel.CHECKING -> kit.oval(Color.TRANSPARENT, tokens.subText)
        }
        creditsButton.visibility = if (menusAllowed) View.VISIBLE else View.GONE
        aboutButton.visibility = if (menusAllowed) View.VISIBLE else View.GONE
        settingsButton.visibility = if (menusAllowed) View.VISIBLE else View.GONE
        actionsBox.visibility = if (actionsVisible()) View.VISIBLE else View.GONE
        enableActions()
        lock.visibility = if (label == VehicleLabel.CHECKING) View.VISIBLE else View.GONE
    }

    /**
     * The meter panel's figures. While driving the odometer shows this trip; parked it shows today's
     * total. Distances are metres from the speed meter. A rise of [issuedToday] means a ticket was just
     * earned, so the stub flies from the gauge to the stub row; the panel's motion level (set by the host
     * before this call) decides how much of this moves at all.
     */
    fun setMeter(tripMeters: Long, todayMeters: Long, nextTicketMeters: Long, tickets: Int, issuedToday: Int,
                 dailyCap: Int, credits: Int, parked: Boolean) {
        val model = MeterModel(tripMeters, todayMeters, nextTicketMeters, tickets, issuedToday, dailyCap, credits,
            contextText, hintText, parked)
        val earned = MeterMath.ticketEarned(meterModel?.issuedToday, issuedToday)
        meter.bind(model, animate = true)
        if (earned) meter.playTicketEarned()
        meterModel = model
        setChipCredits(credits)
    }

    /**
     * The top bar's credits chip follows the meter's figure: where the meter counts up (park, motion on, a
     * rise) the chip counts along on the same 600 ms ease-out curve, so the two never show different
     * numbers; anything else lands at once. The number's box is as wide as the final figure, so the chip
     * does not grow while it counts.
     */
    private fun setChipCredits(value: Int) {
        if (value == chipCredits) return
        chipAnimator?.cancel()
        val from = if (chipCredits < 0) value else chipShown
        chipCredits = value
        val duration = (CHIP_COUNT_MS * motionLevel.scale).toLong()
        fitChipWidth("%,d".format(maxOf(from, value)))
        if (duration <= 0L || !root.isAttachedToWindow || !MeterMath.creditsAnimate(motionLevel, from, value)) {
            showChip(value)
            return
        }
        chipAnimator = ValueAnimator.ofFloat(from.toFloat(), value.toFloat()).apply {
            this.duration = duration
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { showChip((it.animatedValue as Float).let { v -> kotlin.math.round(v).toInt() }) }
            whenDone { chipAnimator = null; showChip(chipCredits); fitChipWidth("%,d".format(chipCredits)) }
            start()
        }
    }

    private fun showChip(value: Int) {
        chipShown = value
        val text = "%,d".format(value)
        if (creditsNumber.text.toString() != text) creditsNumber.text = text
    }

    private fun fitChipWidth(text: String) {
        val width = kotlin.math.ceil(creditsNumber.paint.measureText(text)).toInt() + 2
        val params = creditsNumber.layoutParams ?: return
        if (params.width != width) { params.width = width; creditsNumber.layoutParams = params }
    }

    /**
     * How much the meter may move ([MotionLevel.of]: FULL parked, HALF driving with the motion setting on,
     * NONE otherwise). Dropping to NONE also rebinds the last figures without animation, so a roll or
     * count-up in flight lands on its final value at once instead of finishing its run. Leaving FULL lands
     * a toast that is sliding in or out the same way, and the theater's transitions with it.
     */
    fun setMotionLevel(level: MotionLevel) {
        if (level == motionLevel) return
        motionLevel = level
        meter.setMotion(level)
        player.setAnimated(level == MotionLevel.FULL)
        if (level == MotionLevel.NONE) {
            meterModel?.let { meter.bind(it, animate = false) }
            chipAnimator?.end()
        }
        if (!ToastLook.animates(level)) settleToast()
    }

    /**
     * Reduced motion (setting or OS animations off): the lock ring stands still instead of turning, and the
     * reward cards show each beat's end state at its time (a running one lands at once).
     */
    fun setReduceMotion(value: Boolean) {
        lockSpinner.still = value
        rewards.setReduceMotion(value)
    }

    /** One short status sentence under the meter's ticket stubs (waiting, cap reached, arrival); null hides it. */
    fun setHint(text: String?) {
        hintText = text
        meter.setHint(text)
    }

    /** One line of context from the vehicle or navigation (display only); null hides it. */
    fun setInfoLine(text: String?) {
        contextText = text
        meter.setContextLine(text)
    }

    /**
     * The navigation buttons under the meter; the host passes an empty list when there are none. They act only
     * while parked actions are allowed; otherwise they stay in place, dimmed and disabled, so the meter card
     * keeps its height when the car leaves or enters park and its evenly spread figures never jump.
     */
    fun setParkedActions(items: List<Pair<String, () -> Unit>>) {
        actionRuns = items.map { it.second }
        val labels = items.map { it.first }
        if (labels != actionLabels) {
            actionLabels = labels
            actionsBox.removeAllViews()
            labels.forEachIndexed { index, name ->
                val button = kit.text(name, 26, tokens.text, bold = true).apply {
                    gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    minimumHeight = dp(64); setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                styleAction(button)
                kit.pressable(button) { if (menusAllowed) actionRuns.getOrNull(index)?.invoke() }
                actionsBox.addView(button, actionParams(index))
            }
        }
        actionsBox.visibility = if (actionsVisible()) View.VISIBLE else View.GONE
        enableActions()
    }

    /** Parked actions show when present in the wide layout (narrow hides buttons, design §13-9). */
    private fun actionsVisible() = actionLabels.isNotEmpty() && wide

    /** Enabled in park with parked actions allowed; elsewhere dimmed, untouchable and announced as disabled. */
    private fun enableActions() {
        for (i in 0 until actionsBox.childCount) {
            val button = actionsBox.getChildAt(i)
            button.isEnabled = menusAllowed
            button.alpha = if (menusAllowed) 1f else ACTION_DISABLED_ALPHA
        }
    }

    /**
     * The briefing theater over the scene ([BriefingPlayerView]); null progress or creative closes it. The
     * picture stops at once either way; the theater fades out only where the meter may move fully (park
     * with motion), so leaving park takes it away in the same frame.
     */
    fun setBriefing(progress: AdPlayer.Progress?, creative: Creative?, replay: Boolean) {
        if (progress == null || creative == null) {
            player.hide(animate = motionLevel == MotionLevel.FULL)
            briefingTicket = null
            // A toast that came while the briefing was on screen (its completion) shows now, over the scene.
            toastAfterBriefing?.let { (message, reward, icon) -> toastAfterBriefing = null; showToast(message, reward, icon) }
        } else {
            // Wide, the toast's place is the stage's lower edge, where the theater draws its caption: a toast
            // still up when a briefing opens leaves, and one held for after the last briefing is dropped when
            // the next ticket follows straight on (see [showToast]).
            if (wide && toast.visibility == View.VISIBLE && !toastLeaving) {
                hideToast?.let { root.removeCallbacks(it) }; hideToast = null
                dismissToast()
            }
            if (briefingTicket != null && briefingTicket != progress.adId) toastAfterBriefing = null
            briefingTicket = progress.adId
            player.show(creative, progress, replay)
        }
    }

    /**
     * The engine's surface is on screen ([live]) or hidden (paused, not started, under the opaque theater or
     * the lock). Hidden, the still covers the scene area; turning live, the surface appears under the still,
     * which stays [COVER_HOLD_MS] while the engine draws its first frames and then fades out (in park with
     * motion) or goes (elsewhere). The still and the live scene share one framing, so nothing jumps.
     */
    fun setSceneLive(live: Boolean) {
        if (live == sceneLive) return
        sceneLive = live
        sceneHost.visibility = if (live) View.VISIBLE else View.INVISIBLE
        still.animate().cancel()
        if (!live) {
            still.alpha = 1f
            still.visibility = View.VISIBLE
            return
        }
        val fade = if (motionLevel == MotionLevel.FULL) COVER_FADE_MS else 0L
        still.animate().alpha(0f).setStartDelay(COVER_HOLD_MS).setDuration(fade).setInterpolator(EASE_OUT_CUBIC)
            .withEndAction { if (sceneLive) still.visibility = View.INVISIBLE }.start()
    }

    /** True while a video creative plays in the theater: the host then ticks the ticket with [consumeDeltaMs]. */
    val videoActive: Boolean get() = player.videoActive

    /**
     * True once the theater covers the scene completely (shown and its 0.3 s fade-in done). Until then the
     * host keeps the engine drawing under it, so the fading theater never shows the still or an empty frame.
     */
    val theaterCovering: Boolean get() = player.covering

    /** Called when the theater has just come to cover the scene ([theaterCovering] turned true). */
    var onTheaterCovered: (() -> Unit)?
        get() = player.onCovered
        set(value) { player.onCovered = value }

    /** What the theater's video played since the last call, for AdPlayer.tick (0 when no video plays). */
    fun consumeDeltaMs(): Long = player.consumeDeltaMs()

    /**
     * The arrival in park after a trip (design §7): while the scene's horse arrives, card 1 ("오늘 1.1 km",
     * counting up) and card 2 (this trip's tickets, or the carried-over or welcome ticket) rise at the bottom
     * of the scene and the meter's stubs blink once; [onTheaterOpen] runs as the cards leave (2.6 s, or 2.2 s
     * when nothing is left to watch). The host acknowledges the reveal and releases the player there.
     */
    fun playArrival(todayMeters: Long, ticketsThisTrip: Int, remaining: Int, welcome: Boolean, onTheaterOpen: () -> Unit) =
        rewards.playArrival(todayMeters, ticketsThisTrip, remaining, welcome, onTheaterOpen)

    /** Before the welcome ticket's first playback without a drive: "환영 시청권 1장" for 0.8 s, then [onTheaterOpen]. */
    fun playWelcome(onTheaterOpen: () -> Unit) = rewards.playWelcome(onTheaterOpen)

    fun playChargeBonus(onTheaterOpen: () -> Unit) = rewards.playChargeBonus(onTheaterOpen)

    /**
     * A briefing completed: six stub glyphs rise around the meter's credits figure while it counts up. Only
     * where the meter moves fully (park, motion on); the figure is found by its accessibility label, since
     * [MeterPanel] keeps its views to itself.
     */
    fun playCredit(points: Int) {
        if (motionLevel != MotionLevel.FULL) return
        val found = ArrayList<View>()
        meter.findViewsWithText(found, CREDITS_FIGURE_LABEL, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        val figure = found.firstOrNull { it.isShown } ?: creditsNumber.takeIf { it.isShown } ?: return
        rewards.playCredit(points, figure)
    }

    /** Leaving park: the reward cards and glyphs go in this frame and no hand-off follows. */
    fun cancelRewards() = rewards.cancel()

    /**
     * Bottom-centre toast for [TOAST_MS] (design §8): a 24dp glyph and the message on #313236. [reward]
     * (credits, tickets) adds the 4dp amber bar on the left edge and turns the glyph amber; [icon] picks
     * the glyph, by default the stub for rewards and the info mark otherwise ([ToastLook.icon]). In park
     * it rises 16dp and fades in, and sinks out the same way; anywhere else it appears and leaves in place.
     * A toast already up swaps its content in place and starts its time again. Wide, no toast covers a
     * briefing on screen: it would sit on the caption at the stage's lower edge. It waits until the theater
     * closes (a completion's toast), and is dropped if the next briefing follows straight on: the meter's
     * count-up and ticket stubs carry that news. Narrow, the theater's bottom band makes room for it.
     */
    fun showToast(message: String, reward: Boolean = false, icon: Int? = null) {
        if (wide && player.isShowing) { toastAfterBriefing = Triple(message, reward, icon); return }
        toastText.text = message
        toastReward = reward
        toastIcon.setImageResource(ToastLook.icon(reward, icon))
        styleToast()
        hideToast?.let { root.removeCallbacks(it) }
        val shown = toast.visibility == View.VISIBLE
        if (!shown || toastLeaving) {
            toast.animate().cancel()
            toastLeaving = false
            if (!shown) {
                toast.alpha = 0f
                toast.translationY = dp(TOAST_RISE).toFloat()
                toast.visibility = View.VISIBLE
            }
            if (ToastLook.animates(motionLevel)) {
                toast.animate().alpha(1f).translationY(0f).setDuration(TOAST_IN_MS)
                    .setInterpolator(EASE_OUT_CUBIC).withLayer().start()
            } else {
                toast.alpha = 1f; toast.translationY = 0f
            }
        }
        hideToast = Runnable { hideToast = null; dismissToast() }.also { root.postDelayed(it, TOAST_MS) }
    }

    /** Debug/demo entry in the top bar (never under the lock), so demo states can change in every state. */
    fun addNavChip(label: String, description: String, click: () -> Unit) {
        val chip = kit.text(label, 24, tokens.text, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(20), 0, dp(20), 0); contentDescription = description
            background = kit.rounded(tokens.filled, 24f); setTextColor(tokens.onFilled)
        }
        kit.pressable(chip, click)
        navChipViews += chip
        navChips.addView(chip, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(12) })
    }

    /**
     * The credits page ([CreditsPage]): the balance, today's points, the history rows (newest first,
     * [historyCount] in all) and the stored redemption. Opening it fresh plays its entrance and count-up
     * where the meter moves fully; while it is open, the host calling again with only a new [redemption]
     * moves the selection in place, and a new look or new figures rebuild it without the entrance.
     */
    fun openCredits(
        credits: Int, todayPoints: Int, history: List<CreditEntry>, historyCount: Int, redemption: String,
        week: List<DayBar> = emptyList(), monthPoints: Int = 0,
    ) {
        val model = CreditsPage.Model(credits, todayPoints, history, historyCount, redemption, week, monthPoints)
        val open = creditsPage
        if (menuOpen && open != null && pageTokens == tokens && open.model?.copy(redemption = redemption) == model) {
            open.select(redemption, animate = pageMotion())
            return
        }
        val fresh = !menuOpen
        closeOverlay(); menuOpen = true
        val page = CreditsPage(kit, tokens, actions)
        showPage(page.build(model))
        creditsPage = page
        page.enter(animate = fresh && pageMotion())
    }

    /**
     * The settings page ([SettingsPage]) with the host's values. While it is open with the same look, the
     * host calling again after a toggle slides the switches in place; a new look rebuilds it.
     */
    fun openSettings(motionWhileDriving: Boolean, reduceMotion: Boolean, themeId: String) {
        val open = settingsPage
        if (settingsOpen && open != null && pageTokens == tokens) {
            open.update(motionWhileDriving, reduceMotion, themeId, animate = pageMotion())
            return
        }
        val fresh = !settingsOpen
        closeOverlay(); settingsOpen = true
        val page = SettingsPage(kit, tokens, actions)
        showPage(page.build(motionWhileDriving, reduceMotion, themeId, actions.demoTools()))
        settingsPage = page
        page.enter(animate = fresh && pageMotion())
    }

    /** The "사용 기술" page ([AboutPage]). A new look rebuilds it without the entrance. */
    fun openAbout() {
        if (aboutOpen && pageTokens == tokens) return
        val fresh = !aboutOpen
        closeOverlay(); aboutOpen = true
        val page = AboutPage(kit, tokens, actions)
        showPage(page.build())
        aboutPage = page
        page.enter(animate = fresh && pageMotion())
    }

    /** The system's back inside a page: closes a dialog the page has open and says whether there was one. */
    fun backInPage(): Boolean = aboutPage?.back() ?: false

    fun closeOverlay() {
        menuOpen = false; settingsOpen = false; aboutOpen = false
        aboutPage?.dispose(); aboutPage = null
        creditsPage?.dispose(); creditsPage = null
        settingsPage?.dispose(); settingsPage = null
        pageTokens = null
        overlay.removeAllViews(); overlay.visibility = View.GONE
        coverHome(false)
    }

    private fun showPage(page: View) {
        overlay.visibility = View.VISIBLE
        overlay.setOnClickListener(null)
        overlay.addView(page, match())
        pageTokens = tokens
        coverHome(true)
    }

    /** A page covers the whole home: what lies under it leaves the accessibility tree until it closes. */
    private fun coverHome(covered: Boolean) {
        val mode = if (covered) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        listOf(scene, player, nav, panel, rewardHost).forEach { it.importantForAccessibility = mode }
    }

    /** Pages move where the meter moves fully (parked, motion on); with reduced motion they stand at once. */
    private fun pageMotion() = motionLevel == MotionLevel.FULL

    fun setLock(title: String, body: String) { lockTitle.text = title; lockBody.text = body }

    fun setLockAction(label: String, action: () -> Unit) {
        lockButton.text = label
        kit.pressable(lockButton, action)
    }

    /** The lock's action ("권한 허용"): asking for a permission is a decision, not a wait, so the ring stops showing. */
    fun showLockAction(show: Boolean) {
        lockButton.visibility = if (show) View.VISIBLE else View.GONE
        lockSpinner.visibility = if (show) View.GONE else View.VISIBLE
    }

    fun dp(value: Int) = kit.dp(value)

    private fun layout(width: Int, height: Int) {
        wide = width.toFloat() / height >= 1.0f
        val body = (height - dp(NAV)).coerceAtLeast(1)
        // Design §3: wide keeps the diorama on the left 60% and the meter panel on the right 40%; narrow keeps
        // the panel in the bottom 36% (review 2026-11-08: 44% squeezed the scene).
        val panelWidth = if (wide) width * 2 / 5 else width
        val panelHeight = if (wide) body else body * 36 / 100
        nav.layoutParams = FrameLayout.LayoutParams(-1, dp(NAV), Gravity.TOP)
        val sceneParams = FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(NAV)
            if (wide) rightMargin = panelWidth else bottomMargin = panelHeight
        }
        scene.layoutParams = sceneParams
        // The theater covers exactly the scene (design §6), and the reward cards play at its bottom edge (§7).
        player.layoutParams = FrameLayout.LayoutParams(sceneParams)
        rewards.setStage(sceneParams, compact = !wide)
        panel.layoutParams = if (wide) FrameLayout.LayoutParams(panelWidth, -1, Gravity.END).apply { topMargin = dp(NAV) }
            else FrameLayout.LayoutParams(-1, panelHeight, Gravity.BOTTOM)
        // The column padding is the meter face's margin: 24dp to the sides. On the wide panel the column starts
        // and ends with the scene beside it (no padding above or below), so the two stand at one height.
        panelContent.setPadding(dp(24), if (wide) 0 else dp(12), dp(24), if (wide) 0 else dp(20))
        meter.setCompact(!wide)
        actionsBox.orientation = LinearLayout.VERTICAL
        for (i in 0 until actionsBox.childCount) actionsBox.getChildAt(i).layoutParams = actionParams(i)
        actionsBox.visibility = if (actionsVisible()) View.VISIBLE else View.GONE
        // The toast: bottom centre of the scene. Wide: 96dp up, over the lower edge of the theater's stage.
        // Narrow: just above the panel edge; the theater's bottom band rises by the toast's strip so neither
        // the progress bar nor the time line is covered.
        player.setBottomInset(if (wide) 0 else dp(NARROW_TOAST_STRIP))
        val toastPad = if (wide) dp(20) else dp(16)
        toast.setPadding(dp(TOAST_START), toastPad, dp(TOAST_END), toastPad)
        toast.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = if (wide) dp(96) else panelHeight + dp(6)
            rightMargin = if (wide) panelWidth / 2 else 0
        }
        sceneWidth = if (wide) width - panelWidth else width
        sceneHeight = if (wide) body else body - panelHeight
        toastText.maxWidth = minOf(dp(792), sceneWidth - dp(48)) - dp(TOAST_START + TOAST_END + TOAST_GLYPH + TOAST_GAP)
        lock.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(NAV) }
        updateStill()
        root.requestLayout()
    }

    /**
     * The still behind the engine (the scene area's framing, lined up with the live scene) and the lock's
     * backdrop (the panorama on a landscape window), swapped only when the look or a shape changes.
     */
    private fun updateStill() {
        val key = listOf(skin, tokens.night, sceneWidth, sceneHeight)
        if (key == stillKey) return
        stillKey = key
        still.show(Stills.scene(skin, tokens.night, sceneWidth, sceneHeight))
        lockStill.show(Stills.backdrop(skin, tokens.night, landscape = wide))
    }

    /** The session's theme: the stills behind the engine and the lock follow it. */
    fun setSkin(id: String) {
        if (id == skin) return
        skin = id
        updateStill()
    }

    private fun actionParams(index: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(12) }

    /** A floating surface: 20dp corners like the cards and the toast (the meter face, an instrument, keeps 24dp). */
    private fun styleAction(button: TextView) {
        button.background = kit.rounded(tokens.filled, 20f)
        button.setTextColor(tokens.onFilled)
    }

    private fun buildNav() {
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.setPadding(dp(32), 0, dp(32), 0)
        // The launcher icon in small: the same tile and head, so the app and the dock carry one mark.
        nav.addView(brand, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titleBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0) }
        kit.style(title, 32, bold = true); title.letterSpacing = -0.01f; title.text = "킬로모아"
        // The status chip (design §5): a 12dp dot and 26dp text, read at a glance from the driver's seat.
        val statusRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })
        kit.style(status, 26); status.maxLines = 1; status.ellipsize = TextUtils.TruncateAt.END
        statusRow.addView(status)
        // Explicit wrap params: a match_parent child would not count toward this block's width.
        titleBlock.addView(title, LinearLayout.LayoutParams(-2, -2))
        titleBlock.addView(statusRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        nav.addView(titleBlock)
        nav.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        navChips.orientation = LinearLayout.HORIZONTAL; navChips.gravity = Gravity.CENTER_VERTICAL
        nav.addView(navChips)
        creditsButton.orientation = LinearLayout.HORIZONTAL
        creditsButton.gravity = Gravity.CENTER_VERTICAL
        creditsButton.setPadding(dp(22), 0, dp(22), 0)
        kit.style(creditsNumber, 28, bold = true); creditsNumber.fontFeatureSettings = "tnum"; creditsNumber.gravity = Gravity.END
        kit.style(creditsUnit, 24); creditsUnit.text = "크레딧"
        creditsButton.addView(creditsNumber)
        creditsButton.addView(creditsUnit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        creditsButton.contentDescription = "크레딧 보기"
        kit.pressable(creditsButton) { actions.onCredits() }
        kit.style(aboutButton, 24, bold = true); aboutButton.text = "소개"; aboutButton.gravity = Gravity.CENTER
        aboutButton.setPadding(dp(22), 0, dp(22), 0)
        aboutButton.contentDescription = "소개: 이 장면을 만든 기술"
        kit.pressable(aboutButton) { actions.onAbout() }
        nav.addView(aboutButton, LinearLayout.LayoutParams(-2, dp(56)).apply { leftMargin = dp(12) })
        nav.addView(creditsButton, LinearLayout.LayoutParams(-2, dp(56)).apply { leftMargin = dp(12) })
        nav.addView(settingsButton, LinearLayout.LayoutParams(dp(72), dp(72)).apply { leftMargin = dp(8) })
    }

    /**
     * Panel order, top to bottom: the meter card filling the column (its context line pinned to the
     * bottom edge), then the parked actions at the card's width, 12dp under it and 12dp apart.
     */
    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panelContent.orientation = LinearLayout.VERTICAL
        panelContent.addView(meter, LinearLayout.LayoutParams(-1, 0, 1f))
        actionsBox.orientation = LinearLayout.VERTICAL
        actionsBox.visibility = View.GONE
        panelContent.addView(actionsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        panel.addView(panelContent, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /**
     * The toast: [ToastSurface] (radius 16, the reward bar), the 24dp glyph 16dp before the 26dp text, at
     * most two lines. The start padding is the same with or without the bar, so the glyph never shifts.
     */
    private fun buildToast() {
        toast.orientation = LinearLayout.HORIZONTAL
        toast.gravity = Gravity.CENTER_VERTICAL
        toast.background = toastSurface
        toast.setPadding(dp(TOAST_START), dp(20), dp(TOAST_END), dp(20))  // layout() tightens the vertical padding when narrow
        toast.elevation = dp(20).toFloat()
        toast.visibility = View.GONE
        toastIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        toast.addView(toastIcon, LinearLayout.LayoutParams(dp(TOAST_GLYPH), dp(TOAST_GLYPH)).apply { rightMargin = dp(TOAST_GAP) })
        kit.style(toastText, 26); toastText.maxLines = 2; toastText.ellipsize = TextUtils.TruncateAt.END
        // Two lines split between phrases and evened out, never one word left alone on the second line.
        toastText.breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
        if (android.os.Build.VERSION.SDK_INT >= 33) toastText.lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        toast.addView(toastText)
    }

    /** Toast colours: one dark surface in both looks, amber bar and glyph for rewards, a hairline edge at night. */
    private fun styleToast() {
        toastSurface.color = tokens.toast
        toastSurface.barColor = MeterFace.AMBER
        toastSurface.bar = toastReward
        toastSurface.edgeColor = if (tokens.night) MeterFace.EDGE else Color.TRANSPARENT
        toastSurface.invalidateSelf()
        toastText.setTextColor(tokens.onToast)
        toastIcon.imageTintList = ColorStateList.valueOf(if (toastReward) MeterFace.AMBER else TOAST_NOTICE_GLYPH)
    }

    /** The toast's time is up: sinks 8dp and fades in park, disappears at once anywhere else. */
    private fun dismissToast() {
        if (toast.visibility != View.VISIBLE) return
        toast.animate().cancel()
        if (!ToastLook.animates(motionLevel)) { toastGone(); return }
        toastLeaving = true
        toast.animate().alpha(0f).translationY(dp(TOAST_SINK).toFloat()).setDuration(TOAST_OUT_MS)
            .setInterpolator(EASE_IN_CUBIC).withLayer().withEndAction { toastGone() }.start()
    }

    /** Lands a toast that is moving: one rising ends up, one sinking is gone. */
    private fun settleToast() {
        if (toast.visibility != View.VISIBLE) return
        toast.animate().cancel()
        if (toastLeaving) toastGone() else { toast.alpha = 1f; toast.translationY = 0f }
    }

    private fun toastGone() {
        toastLeaving = false
        toast.visibility = View.GONE
        toast.alpha = 1f; toast.translationY = 0f
    }

    /**
     * The lock (vehicle state unknown, or no speed permission): the day/night scene still behind a card
     * with the ring, the title, the body and, for the permission, the action (design §8). The card sits in
     * the still's sky, above the standing horse: a quarter of the spare height above it, the rest below.
     */
    private fun buildLock() {
        lock.isClickable = true
        lock.addView(lockStill, match())
        lockColumn.orientation = LinearLayout.VERTICAL
        lockColumn.gravity = Gravity.CENTER_HORIZONTAL
        lockColumn.setPadding(dp(32), dp(32), dp(32), dp(32))
        lockColumn.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        lockCard.orientation = LinearLayout.VERTICAL
        lockCard.gravity = Gravity.CENTER_HORIZONTAL
        lockCard.setPadding(dp(56), dp(48), dp(56), dp(48))
        lockCard.elevation = dp(8).toFloat()
        lockSpinner.contentDescription = "확인 중"
        lockCard.addView(lockSpinner, LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(28) })
        kit.style(lockTitle, 36, bold = true); kit.style(lockBody, 28)
        lockTitle.gravity = Gravity.CENTER; lockBody.gravity = Gravity.CENTER
        lockCard.addView(lockTitle, LinearLayout.LayoutParams(-2, -2))
        lockCard.addView(lockBody, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        kit.style(lockButton, 28, bold = true)
        lockButton.gravity = Gravity.CENTER
        lockButton.setPadding(dp(40), 0, dp(40), 0)
        lockButton.visibility = View.GONE
        lockCard.addView(lockButton, LinearLayout.LayoutParams(-2, dp(88)).apply { topMargin = dp(36) })
        lockColumn.addView(lockCard, LinearLayout.LayoutParams(-2, -2))
        lockColumn.addView(View(context), LinearLayout.LayoutParams(1, 0, 3f))
        lock.addView(lockColumn, match())
    }

    /**
     * The lock screen's wait ring: 48dp, 5dp stroke. A quarter arc turns once every 1.1 s over a faint full
     * track; with reduced motion it is a still three-quarter ring. Redraws only while it is on screen.
     */
    private class Spinner(context: Context) : View(context) {
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        var still = false
            set(value) { if (field != value) { field = value; invalidate() } }
        fun colors(arcColor: Int, trackColor: Int) { arc.color = arcColor; track.color = trackColor; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val stroke = 5f * resources.displayMetrics.density
            arc.strokeWidth = stroke; track.strokeWidth = stroke
            rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            canvas.drawOval(rect, track)
            if (still) {
                canvas.drawArc(rect, -90f, 270f, false, arc)
                return
            }
            val start = (SystemClock.uptimeMillis() % PERIOD_MS) * 360f / PERIOD_MS
            canvas.drawArc(rect, start - 90f, 100f, false, arc)
            postInvalidateOnAnimation()
        }
        private companion object { const val PERIOD_MS = 1_100L }
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /**
     * The toast's surface: a rounded rect and, on a reward toast, a bar along the left edge cut to the same
     * rounded corners (the intersection is a path, so its curve is anti-aliased like the rest). An optional
     * hairline edge is drawn inside the shape. Gives the view its rounded outline for the elevation shadow.
     */
    private class ToastSurface(private val radius: Float, private val barWidth: Float, private val edgeWidth: Float) : Drawable() {
        var color = Color.TRANSPARENT
        var barColor = Color.TRANSPARENT
        var edgeColor = Color.TRANSPARENT
        var bar = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val shape = Path()
        private val barStrip = Path()
        private val barShape = Path()

        override fun onBoundsChange(bounds: Rect) {
            box.set(bounds)
            shape.reset(); shape.addRoundRect(box, radius, radius, Path.Direction.CW)
            barStrip.reset(); barStrip.addRect(box.left, box.top, box.left + barWidth, box.bottom, Path.Direction.CW)
            barShape.reset(); barShape.op(shape, barStrip, Path.Op.INTERSECT)
        }

        override fun draw(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawPath(shape, paint)
            if (Color.alpha(edgeColor) != 0) {
                val inset = edgeWidth / 2
                paint.style = Paint.Style.STROKE; paint.strokeWidth = edgeWidth; paint.color = edgeColor
                canvas.drawRoundRect(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset,
                    radius - inset, radius - inset, paint)
            }
            if (bar) {
                paint.style = Paint.Style.FILL
                paint.color = barColor
                canvas.drawPath(barShape, paint)
            }
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radius)
            outline.alpha = 1f
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val NAV = 88
        const val TOAST_MS = 3_200L
        const val GREEN = 0xFF1FA463.toInt()
        const val ORANGE = 0xFFB86F00.toInt()
        /** The launcher icon's background layer (ic_launcher_background): the tile of the app's mark. */
        const val BRAND_TILE = 0xFF131417.toInt()
        /** The still stays over a surface that just appeared while the engine draws, then fades (park with motion). */
        private const val COVER_HOLD_MS = 240L
        /** The chip counts with the meter's credits figure (MeterPanel's count-up). */
        private const val CHIP_COUNT_MS = 600L
        /** A parked action outside park: still in place, plainly unavailable. */
        private const val ACTION_DISABLED_ALPHA = 0.38f
        private const val COVER_FADE_MS = 160L
        /** Narrow only: the theater's progress bar and time line sit this much higher, leaving the toast its own strip. */
        private const val NARROW_TOAST_STRIP = 46
        private const val PLAYING_SUFFIX = " · 브리핑 재생 중"
        /** The start of the meter credits figure's accessibility label ("크레딧 1,230"). */
        private const val CREDITS_FIGURE_LABEL = "크레딧 "
        /** Toast metrics in dp: start and end padding, glyph size and the gap to the text, rise in and sink out. */
        private const val TOAST_START = 24
        private const val TOAST_END = 32
        private const val TOAST_GLYPH = 24
        private const val TOAST_GAP = 16
        private const val TOAST_RISE = 16
        private const val TOAST_SINK = 8
        private const val TOAST_IN_MS = 240L
        private const val TOAST_OUT_MS = 180L
        /** The notice glyph: white at 72% on the #313236 toast (about 7:1), quieter than the text. */
        private const val TOAST_NOTICE_GLYPH = 0xB8FFFFFF.toInt()
        /** Ease-in cubic for leaving, the mirror of [EASE_OUT_CUBIC]. */
        private val EASE_IN_CUBIC = PathInterpolator(0.32f, 0f, 0.67f, 0f)
    }
}

/** The toast's pure decisions (JVM-tested): which glyph it carries and whether it may move at all. */
object ToastLook {
    /** The caller's glyph, else the ticket stub for rewards (credits, tickets) and the info mark for notices. */
    fun icon(reward: Boolean, icon: Int?): Int = icon ?: if (reward) R.drawable.ic_stub else R.drawable.ic_toast_info

    /**
     * A toast slides only where the meter moves fully: in park with motion on. Driving (even with the
     * motion setting), waiting at a light, an unknown state or reduced motion: it appears and leaves in place.
     */
    fun animates(level: MotionLevel): Boolean = level == MotionLevel.FULL
}

/**
 * Shared Pleos building blocks: the type scale in dp, rounded and oval shapes, press feedback, icon buttons and
 * the brand mark. The full-screen pages build on them with their own parts (PageParts.kt).
 */
internal class PleosKit(val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(context).apply { text = value; setTextColor(color); style(this, size, bold) }

    fun style(view: TextView, size: Int, bold: Boolean = false) {
        // Pleos type scale in dp so vehicle panels keep the specified sizes.
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size.toFloat())
        view.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        view.includeFontPadding = false
    }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun oval(color: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); if (stroke != 0) setStroke(dp(3), stroke)
    }

    /** Pleos button interaction: shrink to 95% while pressed, act on release inside the target. */
    fun pressable(view: View, click: () -> Unit) = pressable(view, 0.95f, click)

    /** The same with [pressedScale] (whole cards shrink less than buttons). */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, pressedScale: Float, click: () -> Unit) {
        view.isClickable = true; view.isFocusable = true
        view.setOnClickListener { click() }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            false
        }
    }

    fun iconButton(icon: Int, label: String, click: () -> Unit) = FrameLayout(context).apply {
        contentDescription = label
        addView(ImageView(context).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        pressable(this, click)
    }

    fun tint(button: FrameLayout, fg: Int, bg: Int) {
        (button.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(fg)
        button.background = oval(bg, 0)
    }

    /**
     * The app's mark as the launcher shows it: the adaptive icon's foreground on its #131417 tile, [size]dp
     * square with 25% corners and the meter card's hairline edge, the same in both looks. The 108dp layer is
     * drawn at 1.5x the tile so its 72dp visible part fills the tile and the head keeps the icon's proportions.
     * Decorative: the title next to it names the app.
     */
    fun brandMark(size: Int): FrameLayout = FrameLayout(context).apply {
        background = rounded(HomeView.BRAND_TILE, size / 4f, 1, MeterFace.EDGE)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_launcher_foreground) },
            FrameLayout.LayoutParams(dp(size * 3 / 2), dp(size * 3 / 2), Gravity.CENTER))
    }

}
