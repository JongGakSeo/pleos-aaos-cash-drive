package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.R
import ai.cashdrive.core.playback.AdPlayback
import ai.cashdrive.core.playback.MockPlaybackSession
import ai.cashdrive.core.playback.VehicleState
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The reward sequence on screen (design §7): two cards high in the scene's sky and, on a completion,
 * six stub glyphs rising around the meter's credits figure (a small halo that stays in its section). It plays a [RewardPlan] from [RewardScript]
 * on the main thread's clock through [RewardRun]; the host decides when a sequence starts, what it counts
 * and what the hand-off does (acknowledge the reveal, release the player).
 * - Card 1 rises 24dp and fades in (300 ms), centred while it is alone, and counts today's distance up
 *   from 0.0 km (500 ms). Card 2 clicks in to its right from -8 degrees and 0.9 scale with a 1.04
 *   overshoot (360 ms) while card 1 slides over to make room; [onTicketsStamped] fires with it.
 * - At the hand-off both cards rise 24dp and fade out (200 ms) over the opening theater.
 * - Card 1 is white (night #1D1E21 with a hairline edge), card 2 an amber ticket with notched sides that
 *   catches a light sweep once it has landed; both carry a glyph badge and the lead over a large
 *   "value unit" ([RewardCard]). The pair stands at 11% of the scene's height (6% narrow), over the sky.
 * Transform and alpha only. With reduced motion every beat shows its end state at its time. [cancel]
 * (leaving park) takes everything away in the same frame and the hand-off never comes.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class RewardSequence internal constructor(
    private val host: FrameLayout,
    private val kit: PleosKit,
    private var tokens: PleosTokens,
) {
    /** Card 2 came in: the host blinks the meter's stub row once (design §7 step 3). */
    var onTicketsStamped: (() -> Unit)? = null

    private val context: Context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private val stage = SkyStage(context)
    private val row = LinearLayout(context)
    private val distanceCard = RewardCard(context, kit, R.drawable.ic_route, ticket = false)
    private val ticketCard = RewardCard(context, kit, R.drawable.ic_stub, ticket = true)
    private val glyphs = List(HALO.size) { StubGlyphView(context) }
    private val glyphAnimators = mutableListOf<ValueAnimator>()
    private var burstWait: ViewTreeObserver.OnPreDrawListener? = null
    private var frameWait: ViewTreeObserver.OnPreDrawListener? = null
    private var run: RewardRun? = null
    /** When the sequence's clock started: its first drawn frame (negative until then). */
    private var startedAt = -1L
    private var handOff: (() -> Unit)? = null
    private var reduceMotion = false
    private var compact = false
    private var dismissing = false
    private var distanceTenths = 0L
    private var countAnimator: ValueAnimator? = null
    private var stampAnimator: ValueAnimator? = null
    private val xy = IntArray(2)
    private val hostXy = IntArray(2)
    private val pump = Runnable { pump() }

    private val beats = object : RewardRun.Listener {
        override fun onBeat(step: RewardStep) {
            when (step.beat) {
                RewardBeat.ARRIVE -> Unit
                RewardBeat.DISTANCE -> showDistance()
                RewardBeat.TICKETS -> showTickets()
                RewardBeat.DISMISS -> dismiss()
            }
        }

        override fun onHandOff() {
            log("hand-off")
            val next = handOff
            handOff = null
            next?.invoke()
        }

        override fun onEnd() {
            run = null
            stopCardAnimations()
            hideCards()
        }
    }

    /** True from the start of a sequence until its cards are gone or it is cancelled. */
    val running: Boolean get() = run != null

    init {
        host.clipChildren = false
        stage.clipChildren = false
        stage.clipToPadding = false
        stage.visibility = View.GONE
        stage.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false
        row.clipToPadding = false
        row.addView(distanceCard, LinearLayout.LayoutParams(-2, -2))
        row.addView(ticketCard, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(CARD_GAP) })
        stage.addView(row, FrameLayout.LayoutParams(-2, -2))
        host.addView(stage, FrameLayout.LayoutParams(-1, -1))
        glyphs.forEach { glyph ->
            glyph.visibility = View.GONE
            glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            host.addView(glyph, FrameLayout.LayoutParams(dp(GLYPH_W), dp(GLYPH_H), Gravity.TOP or Gravity.START))
        }
        hideCards()
        applySize()
        setTokens(tokens)
    }

    /**
     * Where the cards play: the scene's area, as the host lays the scene out ([sceneParams] are the scene's
     * own layout params). [compact] is the narrow layout: smaller cards closer to the edge.
     */
    fun setStage(sceneParams: FrameLayout.LayoutParams, compact: Boolean) {
        stage.layoutParams = FrameLayout.LayoutParams(sceneParams)
        if (compact != this.compact) {
            this.compact = compact
            applySize()
        }
    }

    fun setTokens(value: PleosTokens) {
        tokens = value
        distanceCard.setTokens(value)
        ticketCard.setTokens(value)
    }

    /** Reduced motion: a running beat lands on its end state now and the next ones show at once. */
    fun setReduceMotion(value: Boolean) {
        if (value == reduceMotion) return
        reduceMotion = value
        if (value) {
            land()
            cancelGlyphs()
        }
    }

    /**
     * Parked after a trip (design §7): the scene's arrival, card 1 with [todayMeters], card 2 with this
     * trip's tickets ([RewardScript.arrival]), then [onTheaterOpen] as the cards leave, 2.6 s after the start
     * (2.2 s when there is nothing to watch, and no theater then).
     */
    fun playArrival(todayMeters: Long, ticketsThisTrip: Int, remaining: Int, welcome: Boolean, onTheaterOpen: () -> Unit) {
        log("arrival today=${todayMeters}m tickets=$ticketsThisTrip remaining=$remaining welcome=$welcome")
        start(RewardScript.arrival(ticketsThisTrip, remaining, welcome), MeterMath.tenths(todayMeters), onTheaterOpen)
    }

    /** The welcome ticket's first playback without a drive: card 2 alone for 0.8 s, then [onTheaterOpen]. */
    fun playWelcome(onTheaterOpen: () -> Unit) {
        log("welcome")
        start(RewardScript.welcome(), 0L, onTheaterOpen)
    }

    /** The charge bonus in park: card 2 alone ("충전 보너스 1장") for 1.0 s, then [onTheaterOpen]. */
    fun playChargeBonus(onTheaterOpen: () -> Unit) {
        log("charge bonus")
        start(RewardScript.chargeBonus(), 0L, onTheaterOpen)
    }

    /**
     * A completion in park (design §7): six stub glyphs pop out around [anchor] (the meter's credits figure),
     * drift a little and fade within 0.6 s while the figure counts up. They start clear of the figure (above
     * and below it, never over its digits), the upper ones only rising so they stay under the section's
     * rule, in amber at 70% so the figure stays the brightest thing. Aimed once the layout after the
     * meter's new figure is done (its box widens with an added digit). Six views on fixed paths, not
     * particles; nothing with reduced motion. The host calls this only where the meter moves fully.
     */
    fun playCredit(points: Int, anchor: View) {
        if (points <= 0 || reduceMotion) return
        cancelGlyphs()
        val wait = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                dropBurstWait()
                burst(points, anchor)
                return true
            }
        }
        burstWait = wait
        host.viewTreeObserver.addOnPreDrawListener(wait)
        host.invalidate()
    }

    private fun burst(points: Int, anchor: View) {
        if (reduceMotion || !anchor.isShown || anchor.width <= 0 || !host.isLaidOut) return
        anchor.getLocationInWindow(xy)
        host.getLocationInWindow(hostXy)
        val cx = xy[0] - hostXy[0] + anchor.width / 2f
        val cy = xy[1] - hostXy[1] + anchor.height / 2f
        val hw = anchor.width / 2f
        val hh = anchor.height / 2f
        val rise = dp(HALO_RISE).toFloat()
        val halfW = dp(GLYPH_W) / 2f
        val halfH = dp(GLYPH_H) / 2f
        log("credit +$points halo around ${cx.toInt()},${cy.toInt()} (${anchor.width}x${anchor.height})")
        val clear = dp(HALO_CLEAR).toFloat()
        HALO.forEachIndexed { i, ray ->
            val glyph = glyphs[i]
            // Starts just past the figure's top or bottom edge (the glyph's own half height and HALO_CLEAR
            // beyond it), so no glyph ever lies on a digit, then drifts out by (dx, dy) and rises.
            val fromX = cx + ray.fx * hw + dp(ray.ox)
            val fromY = cy + ray.fy * (hh + halfH + clear) + dp(ray.oy)
            val toX = fromX + dp(ray.dx)
            val toY = fromY + dp(ray.dy) - rise
            glyph.alpha = 0f
            glyph.rotation = 0f
            glyph.scaleX = GLYPH_SCALE_FROM; glyph.scaleY = GLYPH_SCALE_FROM
            glyph.translationX = fromX - halfW
            glyph.translationY = fromY - halfH
            glyph.visibility = View.VISIBLE
            glyphAnimators += ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = ray.delay
                duration = BURST_MS - ray.delay
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val move = EASE_OUT_CUBIC.getInterpolation(t)
                    glyph.translationX = fromX + (toX - fromX) * move - halfW
                    glyph.translationY = fromY + (toY - fromY) * move - halfH
                    glyph.rotation = ray.turn * move
                    val s = GLYPH_SCALE_FROM + (1f - GLYPH_SCALE_FROM) * EASE_OUT_CUBIC.getInterpolation((t / 0.5f).coerceAtMost(1f))
                    glyph.scaleX = s; glyph.scaleY = s
                    glyph.alpha = GLYPH_ALPHA * when {
                        t < GLYPH_IN -> EASE_OUT_CUBIC.getInterpolation(t / GLYPH_IN)
                        t > GLYPH_OUT -> 1f - EASE_IN_CUBIC.getInterpolation((t - GLYPH_OUT) / (1f - GLYPH_OUT))
                        else -> 1f
                    }
                }
                whenDone { glyph.visibility = View.GONE }
                start()
            }
        }
    }

    /** Leaving park (Review Focus 4): cards and glyphs go in this frame, animators stop, no hand-off follows. */
    fun cancel() {
        val current = run
        run = null
        handOff = null
        handler.removeCallbacks(pump)
        dropFrameWait()
        if (current != null) {
            current.cancel()
            log("cancel")
        }
        stopCardAnimations()
        hideCards()
        cancelGlyphs()
    }

    private fun start(plan: RewardPlan, tenths: Long, onHandOff: () -> Unit) {
        cancel()
        handOff = onHandOff
        distanceTenths = tenths
        distanceCard.contentDescription = "오늘 ${RewardScript.distanceNumber(tenths)} km"
        distanceCard.setLine("오늘 달린 거리", RewardScript.distanceNumber(tenths), "km", UNIT_GAP_KM, tabular = true)
        distanceCard.fixValueWidth(RewardScript.distanceNumber(tenths))
        plan.tickets?.let {
            ticketCard.setLine(it.lead, it.count.toString(), "장", UNIT_GAP_COUNT, tabular = false)
            ticketCard.fixValueWidth(it.count.toString())
            ticketCard.contentDescription = it.text
        }
        // Laid out from the start (invisible) so card 1 can sit centred while alone and slide aside for card 2.
        distanceCard.visibility = if (plan.has(RewardBeat.DISTANCE)) View.INVISIBLE else View.GONE
        ticketCard.visibility = if (plan.has(RewardBeat.TICKETS)) View.INVISIBLE else View.GONE
        dismissing = false
        stage.visibility = View.VISIBLE
        run = RewardRun(plan, beats)
        startedAt = -1L
        startOnFirstFrame()
    }

    /**
     * The clock starts with the first frame drawn after the start, not at the call: a window that is not
     * drawing yet (a launch still busy) would otherwise spend the cards' time where nobody sees it.
     */
    private fun startOnFirstFrame() {
        dropFrameWait()
        val wait = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                dropFrameWait()
                if (run != null && startedAt < 0) {
                    startedAt = SystemClock.uptimeMillis()
                    log("first frame")
                    pump()
                }
                return true
            }
        }
        frameWait = wait
        host.viewTreeObserver.addOnPreDrawListener(wait)
        host.invalidate()
    }

    private fun dropFrameWait() {
        val wait = frameWait ?: return
        frameWait = null
        val observer = host.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(wait)
    }

    /** Fires what came due and sleeps until the next beat. */
    private fun pump() {
        handler.removeCallbacks(pump)
        val current = run ?: return
        if (startedAt < 0) return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        current.advanceTo(elapsed)
        // A callback may have cancelled this run or started another one.
        if (run !== current) return
        val next = current.nextDueMs() ?: return
        handler.postDelayed(pump, (next - elapsed).coerceAtLeast(0))
    }

    private fun showDistance() {
        val card = distanceCard
        card.visibility = View.VISIBLE
        // Alone it stands in the middle; card 2's slot (already laid out) is kept free to its right.
        card.translationX = if (ticketCard.visibility == View.GONE) 0f else pairShift()
        if (reduceMotion) {
            card.alpha = 1f; card.translationY = 0f
            card.setValue(RewardScript.distanceNumber(distanceTenths))
            return
        }
        card.alpha = 0f
        card.translationY = dp(RISE).toFloat()
        card.setValue(RewardScript.distanceNumber(0))
        card.animate().alpha(1f).translationY(0f).setDuration(RISE_MS).setInterpolator(EASE_OUT_CUBIC).withLayer().start()
        val target = distanceTenths
        // Rounded, not truncated: the last tenth lands at about 70% of the run rather than on the final frame.
        countAnimator = ValueAnimator.ofFloat(0f, target.toFloat()).apply {
            duration = COUNT_MS
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { card.setValue(RewardScript.distanceNumber((it.animatedValue as Float).roundToLong())) }
            whenDone { countAnimator = null; card.setValue(RewardScript.distanceNumber(target)) }
            start()
        }
    }

    private fun showTickets() {
        onTicketsStamped?.invoke()
        val card = ticketCard
        card.visibility = View.VISIBLE
        if (reduceMotion) {
            distanceCard.translationX = 0f
            settleStamp(card)
            return
        }
        if (distanceCard.visibility == View.VISIBLE && distanceCard.translationX != 0f) {
            distanceCard.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(EASE_OUT_CUBIC).start()
        }
        card.alpha = 0f
        card.rotation = STAMP_TURN
        card.scaleX = STAMP_FROM; card.scaleY = STAMP_FROM
        stampAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = STAMP_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                card.alpha = EASE_OUT_CUBIC.getInterpolation((t / STAMP_FADE).coerceAtMost(1f))
                card.rotation = STAMP_TURN * (1f - EASE_OUT_CUBIC.getInterpolation(t))
                val s = stampScale(t)
                card.scaleX = s; card.scaleY = s
            }
            whenDone { stampAnimator = null; settleStamp(card); ticketCard.sweep() }
            start()
        }
    }

    private fun dismiss() {
        dismissing = true
        countAnimator?.end()
        stampAnimator?.end()
        val shown = listOf(distanceCard, ticketCard).filter { it.visibility == View.VISIBLE }
        if (reduceMotion) {
            hideCards()
            return
        }
        // Ease-out: the cards lift from the first frame of their 200 ms (an ease-in left them standing while
        // the theater faded in, then dropped them at once).
        shown.forEach { card ->
            card.animate().cancel()
            card.translationX = 0f
            card.animate().alpha(0f).translationY(-dp(RISE).toFloat()).setDuration(RewardScript.DISMISS_MS)
                .setInterpolator(EASE_OUT_CUBIC).withLayer().start()
        }
    }

    /** Lands every running card animation on its end state (reduced motion switched on mid-sequence). */
    private fun land() {
        if (run == null) return
        if (dismissing) { stopCardAnimations(); hideCards(); return }
        countAnimator?.end()
        stampAnimator?.end()
        listOf(distanceCard, ticketCard).filter { it.visibility == View.VISIBLE }.forEach { card ->
            card.animate().cancel()
            card.alpha = 1f; card.translationY = 0f
            if (ticketCard.visibility == View.VISIBLE) card.translationX = 0f
        }
    }

    private fun settleStamp(card: View) {
        card.alpha = 1f; card.rotation = 0f; card.scaleX = 1f; card.scaleY = 1f
    }

    private fun stopCardAnimations() {
        countAnimator?.cancel(); countAnimator = null
        stampAnimator?.cancel(); stampAnimator = null
        ticketCard.stopSweep()
        distanceCard.animate().cancel()
        ticketCard.animate().cancel()
    }

    private fun hideCards() {
        dismissing = false
        listOf(distanceCard, ticketCard).forEach { card ->
            card.visibility = View.GONE
            card.alpha = 1f; card.translationX = 0f; card.translationY = 0f
            card.rotation = 0f; card.scaleX = 1f; card.scaleY = 1f
        }
        stage.visibility = View.GONE
    }

    private fun dropBurstWait() {
        val wait = burstWait ?: return
        burstWait = null
        val observer = host.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(wait)
    }

    private fun cancelGlyphs() {
        dropBurstWait()
        val running = glyphAnimators.toList()
        glyphAnimators.clear()
        running.forEach { it.cancel() }
        glyphs.forEach { it.visibility = View.GONE }
    }

    /** How far card 1 sits right of its place while card 2's slot is still empty: half of that slot. */
    private fun pairShift(): Float {
        val slot = ticketCard.width.takeIf { it > 0 } ?: ticketCard.measuredWidth
        return if (slot <= 0) 0f else (slot + dp(CARD_GAP)) / 2f
    }

    private fun applySize() {
        distanceCard.setCompact(compact)
        ticketCard.setCompact(compact)
        stage.topFraction = if (compact) TOP_COMPACT else TOP
        (ticketCard.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.leftMargin = dp(CARD_GAP)
            ticketCard.layoutParams = it
        }
    }

    private fun dp(value: Int) = kit.dp(value)

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    /**
     * One glyph of the completion halo: where it starts ([fx] times the figure's half width plus [ox] dp
     * across; just above the figure for [fy] -1 or just below it for 1, shifted [oy] dp down, or up when
     * negative, always clear of the digits), how far it drifts ([dx], [dy] dp, before the common rise), its
     * start delay and its final turn.
     */
    private class Ray(val fx: Float, val ox: Int, val fy: Float, val oy: Int, val dx: Int, val dy: Int, val delay: Long, val turn: Float)

    private companion object {
        const val TAG = "CashDriveReward"
        const val CARD_GAP = 16
        /** The cards' top edge as a share of the scene's height: over the sky, clear of the horse's head. */
        const val TOP = 0.11f
        const val TOP_COMPACT = 0.06f
        const val RISE = 24
        const val RISE_MS = 300L
        const val COUNT_MS = 500L
        const val SLIDE_MS = 300L
        const val STAMP_MS = 360L
        const val STAMP_TURN = -8f
        const val STAMP_FROM = 0.9f
        const val STAMP_PEAK = 1.04f
        /** Share of the stamp spent reaching the overshoot, and share spent fading in. */
        const val STAMP_RISE = 0.6f
        const val STAMP_FADE = 0.4f
        const val UNIT_GAP_KM = 8
        const val UNIT_GAP_COUNT = 4
        const val GLYPH_W = 26
        const val GLYPH_H = 18
        const val GLYPH_SCALE_FROM = 0.6f
        const val GLYPH_IN = 0.2f
        const val GLYPH_OUT = 0.55f
        /** The glyphs' peak alpha: amber a tone under the figure's, so the figure stays the brightest thing. */
        const val GLYPH_ALPHA = 0.7f
        const val BURST_MS = 600L
        /** Space between the figure's edge and a glyph's near edge where it starts, in dp. */
        const val HALO_CLEAR = 4
        /** How far every glyph floats up over the run, in dp. */
        const val HALO_RISE = 10
        /**
         * Above and below the figure, which sits at the face's left padding with "크레딧" to its right: three
         * glyphs over it (and one over the unit) that only rise, two under it that drift down and out.
         */
        val HALO = listOf(
            Ray(-0.8f, 0, -1f, -2, -6, -2, 0, -12f),   // above, left, a little higher
            Ray(0.1f, 0, -1f, -10, 0, -6, 40, 6f),     // above, middle, the top of the arc
            Ray(0.95f, 0, -1f, -4, 6, -2, 80, 12f),    // above, right
            Ray(1f, 44, -1f, 10, 10, 0, 20, 18f),      // above the unit, which sits lower on the baseline
            Ray(-0.5f, 0, 1f, 0, -8, 16, 60, -16f),    // below, left
            Ray(0.7f, 0, 1f, 4, 8, 16, 100, 20f),      // below, right
        )
        val EASE_IN_CUBIC = PathInterpolator(0.32f, 0f, 0.67f, 0f)

        /** 0.9 -> 1.04 (ease-out) over the first 60%, then back to 1.0 (ease-in-out): the "click" of card 2. */
        fun stampScale(t: Float): Float = if (t < STAMP_RISE) {
            STAMP_FROM + (STAMP_PEAK - STAMP_FROM) * EASE_OUT_CUBIC.getInterpolation(t / STAMP_RISE)
        } else {
            val u = (t - STAMP_RISE) / (1f - STAMP_RISE)
            STAMP_PEAK + (1f - STAMP_PEAK) * (u * u * (3f - 2f * u))
        }
    }
}

/**
 * The stage of the reward cards: the scene's area, with the row of cards centred across it and its top
 * edge at [topFraction] of the height (the cards stand in the sky, over the horse).
 */
@SuppressLint("ViewConstructor")
private class SkyStage(context: Context) : FrameLayout(context) {
    var topFraction = 0.11f
        set(value) { if (field != value) { field = value; requestLayout() } }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val x = (w - child.measuredWidth) / 2
            val y = (h * topFraction).toInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

/**
 * One reward card: a glyph badge and the lead on the first line, then the value with its unit on one
 * baseline (wide 26/88/34dp, narrow 22/60/26dp, the value bold). A counting value uses tabular figures in
 * a box as wide as its final text, so the count-up never moves the unit; a still count keeps proportional
 * figures so "1장" stays tight. Card 1 is white (#1D1E21 with a hairline edge at night), radius 24dp; card 2
 * ([ticket]) is an amber ticket with dark ink in both looks: half-circle notches in its sides, a dashed
 * inner border and, once it has landed, one light sweep ([sweep]). Soft shadow on both.
 */
@SuppressLint("ViewConstructor")
private class RewardCard(context: Context, private val kit: PleosKit, icon: Int, private val ticket: Boolean) : LinearLayout(context) {
    private val head = LinearLayout(context)
    private val badge = FrameLayout(context)
    private val glyph = ImageView(context)
    private val words = LinearLayout(context)
    private val lead = TextView(context)
    private val value = TextView(context)
    private val unit = TextView(context)
    private val ticketFace = if (ticket) TicketFace(kit.dp(1).toFloat()) else null
    private var sweepAnimator: ValueAnimator? = null
    private var compact = false
    private var unitGap = 0
    private var fixedValue: String? = null

    init {
        orientation = VERTICAL
        glyph.setImageResource(icon)
        glyph.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        badge.addView(glyph)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        head.addView(badge)
        head.addView(lead)
        // Wrap, not the vertical default (match parent): the first line may be the card's widest.
        addView(head, LayoutParams(-2, -2))
        words.orientation = HORIZONTAL
        words.isBaselineAligned = true
        words.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        value.gravity = Gravity.END
        listOf(lead, value, unit).forEach { it.maxLines = 1; it.includeFontPadding = false }
        words.addView(value)
        words.addView(unit)
        addView(words)
        elevation = kit.dp(ELEVATION).toFloat()
        if (ticketFace != null) {
            // The notched outline is not convex, so the shadow follows the card's rounded bounds.
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = false
        }
        setCompact(false)
    }

    fun setTokens(tokens: PleosTokens) {
        val face = ticketFace
        if (face != null) {
            background = face
            badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TICKET_BADGE) }
            glyph.imageTintList = ColorStateList.valueOf(TICKET_INK)
            lead.setTextColor(TICKET_SOFT_INK)
            value.setTextColor(TICKET_INK)
            unit.setTextColor(TICKET_SOFT_INK)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, kit.dp(RADIUS).toFloat())
                    outline.alpha = 0.6f
                }
            }
            return
        }
        background = GradientDrawable().apply {
            setColor(if (tokens.night) NIGHT else DAY)
            cornerRadius = kit.dp(RADIUS).toFloat()
            if (tokens.night) setStroke(1, MeterFace.EDGE)
        }
        badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WASH) }
        glyph.imageTintList = ColorStateList.valueOf(tokens.accent)
        lead.setTextColor(tokens.subText)
        value.setTextColor(tokens.text)
        unit.setTextColor(tokens.subText)
    }

    /** Narrow layout: a 40dp badge and 22/60/26dp words, tighter padding. */
    fun setCompact(on: Boolean) {
        compact = on
        val badgeSize = kit.dp(if (on) 40 else 52)
        val glyphSize = kit.dp(if (on) 24 else 32)
        badge.layoutParams = LayoutParams(badgeSize, badgeSize)
        glyph.layoutParams = FrameLayout.LayoutParams(glyphSize, glyphSize, Gravity.CENTER)
        lead.layoutParams = LayoutParams(-2, -2).apply { leftMargin = kit.dp(if (on) 10 else 14) }
        words.layoutParams = LayoutParams(-2, -2).apply { topMargin = kit.dp(if (on) 8 else 12) }
        kit.style(lead, if (on) 22 else 26, bold = true)
        kit.style(value, if (on) 60 else 88, bold = true)
        kit.style(unit, if (on) 26 else 34, bold = ticket)
        value.letterSpacing = -0.02f
        val padX = kit.dp(if (on) 24 else 36)
        val padY = kit.dp(if (on) 18 else 26)
        setPadding(padX, padY, padX + kit.dp(if (on) 8 else 12), padY)
        applyGaps()
        fixedValue?.let { fixValueWidth(it) }
    }

    /**
     * The words; [gap] dp between the value and the unit ("1.1 km" spaced, "2장" tight). [tabular] for a
     * value that counts up, so its digits keep their places.
     */
    fun setLine(leadText: String, valueText: String, unitText: String, gap: Int, tabular: Boolean) {
        lead.text = leadText
        value.fontFeatureSettings = if (tabular) "tnum" else null
        value.text = valueText
        unit.text = unitText
        unitGap = gap
        applyGaps()
    }

    fun setValue(text: String) {
        if (value.text.toString() != text) value.text = text
    }

    /** Sizes the value's box to [text] (the final figure), so shorter figures during a count-up align right. */
    fun fixValueWidth(text: String) {
        fixedValue = text
        val width = ceil(value.paint.measureText(text)).toInt() + 2
        val params = value.layoutParams as LayoutParams
        if (params.width != width) {
            params.width = width
            value.layoutParams = params
        }
    }

    /** Card 2 only: one diagonal light sweep across the ticket (420 ms), drawn inside its outline. */
    fun sweep() {
        val face = ticketFace ?: return
        stopSweep()
        if (!isAttachedToWindow) return
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { face.sweep = it.animatedValue as Float }
            whenDone { sweepAnimator = null; face.sweep = -1f }
            start()
        }
    }

    fun stopSweep() {
        sweepAnimator?.cancel(); sweepAnimator = null
        ticketFace?.sweep = -1f
    }

    /** 0dp between the lead and the value line's start, [unitGap] dp before the unit. */
    private fun applyGaps() {
        (unit.layoutParams as LayoutParams).leftMargin = kit.dp(unitGap)
        words.requestLayout()
    }

    private companion object {
        const val DAY = 0xFFFFFFFF.toInt()
        const val NIGHT = 0xFF1D1E21.toInt()
        /** Amber at 16%: a cream disc on the white card, a warm dark disc on the night card. */
        const val WASH = 0x29E0A93C
        /** The ticket's ink: a deep warm brown on the amber (contrast 8:1), softer for the lead and unit. */
        const val TICKET_INK = 0xFF2B1D05.toInt()
        const val TICKET_SOFT_INK = 0xCC2B1D05.toInt()
        const val TICKET_BADGE = 0x33FFFFFF
        const val RADIUS = 24
        const val ELEVATION = 16
        const val SWEEP_MS = 420L
    }
}

/**
 * Card 2's face: an amber ticket with a half-circle notch in each side (at half height), a dashed inner
 * border 8dp in, and an optional light band ([sweep] 0..1 across, negative for none) clipped to the shape.
 */
private class TicketFace(private val dp: Float) : Drawable() {
    private val shape = Path()
    private val notches = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MeterFace.AMBER }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = MeterFace.AMBER_DEEP; strokeWidth = 2f * dp
        pathEffect = DashPathEffect(floatArrayOf(7f * dp, 5f * dp), 0f)
    }
    private val inner = Path()
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    var sweep = -1f
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val corner = 24f * dp
        val notch = minOf(16f * dp, h * 0.14f)
        shape.reset()
        shape.addRoundRect(0f, 0f, w, h, corner, corner, Path.Direction.CW)
        notches.reset()
        notches.addCircle(0f, h / 2f, notch, Path.Direction.CW)
        notches.addCircle(w, h / 2f, notch, Path.Direction.CW)
        shape.op(notches, Path.Op.DIFFERENCE)
        val inset = 8f * dp
        inner.reset()
        inner.addRoundRect(inset, inset, w - inset, h - inset, corner - inset, corner - inset, Path.Direction.CW)
        val innerNotches = Path().apply {
            addCircle(0f, h / 2f, notch + inset, Path.Direction.CW)
            addCircle(w, h / 2f, notch + inset, Path.Direction.CW)
        }
        inner.op(innerNotches, Path.Op.DIFFERENCE)
        val band = w * 0.35f
        light.shader = LinearGradient(0f, 0f, band, 0f,
            intArrayOf(0x00FFFFFF, 0x73FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawPath(shape, fill)
        canvas.drawPath(inner, border)
        if (sweep >= 0f) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val band = w * 0.35f
            canvas.save()
            canvas.clipPath(shape)
            canvas.translate(-band + (w + band) * sweep, 0f)
            box.set(0f, -h, band, 2f * h)
            canvas.rotate(12f, band / 2f, h / 2f)
            canvas.drawRect(box, light)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; border.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The beats of the reward sequence (design §7), in the order they come. */
enum class RewardBeat {
    /** The scene's own arrival (Godot, 1.2 s): the horse runs in and stops. Nothing native is on screen yet. */
    ARRIVE,
    /** Card 1 rises: the route glyph and today's distance counting up. */
    DISTANCE,
    /** Card 2 clicks in beside it: the stub glyph and the tickets; the meter's stub row blinks once. */
    TICKETS,
    /** Both cards leave upward and the stage goes back to the host, which may open the theater. */
    DISMISS,
}

/** One beat, [startMs] after the sequence starts, on screen for [durationMs]. */
data class RewardStep(val beat: RewardBeat, val startMs: Long, val durationMs: Long)

/** Card 2's wording: "[lead] [count]장" ("시청권 2장", "이어서 볼 시청권 3장", "환영 시청권 1장"). */
data class TicketWording(val lead: String, val count: Int) {
    val text: String get() = "$lead ${count}장"
}

/** A whole sequence: its beats in time order and card 2's wording (null when card 2 does not come). */
data class RewardPlan(val steps: List<RewardStep>, val tickets: TicketWording?) {
    /** When the stage goes back to the host: the dismiss beat's start (design §7 step 4), else the end. */
    val handOffMs: Long get() = step(RewardBeat.DISMISS)?.startMs ?: endMs

    /** When the last beat ends and nothing of the sequence is left on screen. */
    val endMs: Long get() = steps.maxOfOrNull { it.startMs + it.durationMs } ?: 0L

    fun has(beat: RewardBeat): Boolean = steps.any { it.beat == beat }
    fun step(beat: RewardBeat): RewardStep? = steps.firstOrNull { it.beat == beat }
}

/**
 * Today's issue count when the current trip began to drive, and that day ("YYYYMMDD"), so the arrival
 * can tell the tickets of this trip (design §13-4). [encode]/[decode] keep it across a process restart.
 */
data class TripMark(val issued: Int, val day: String) {
    fun encode(): String = "$issued@$day"

    companion object {
        /** The mark written by [encode]; anything else (missing, malformed, negative) is no mark. */
        fun decode(value: String?): TripMark? {
            val parts = value?.split('@') ?: return null
            if (parts.size != 2 || parts[1].isBlank()) return null
            val issued = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return TripMark(issued, parts[1])
        }
    }
}

/**
 * The reward sequence's timetable and wording (design §7, §13-4), pure so it runs in JVM tests. The host
 * plays a plan with [RewardRun]; the views only draw the beats.
 * - Arrival with tickets to watch: the scene's arrival (0-1.2 s), card 1 (1.2-1.9 s), card 2 (1.9-2.6 s),
 *   then both leave in 0.2 s while the theater opens at 2.6 s.
 * - Arrival with nothing to watch: card 1 alone for 1.0 s, then it leaves; no briefing follows.
 * - The welcome ticket's first playback without a drive: card 2 alone for 0.8 s, then the theater.
 * - A charge bonus in park: card 2 alone ("충전 보너스 1장") for 1.0 s, then the theater.
 * Card 2 counts the tickets issued on this trip; a trip that earned none but left tickets from before
 * says "이어서 볼 시청권 N장", and the unplayed welcome ticket says "환영 시청권 1장".
 */
object RewardScript {
    const val ARRIVE_MS = 1_200L
    const val CARD_MS = 700L
    const val ALONE_MS = 1_000L
    const val WELCOME_MS = 800L
    const val BONUS_MS = 1_000L
    const val DISMISS_MS = 200L

    private val WELCOME = TicketWording("환영 시청권", 1)
    private val CHARGE_BONUS = TicketWording("충전 보너스", 1)

    /**
     * Parked after a trip. [ticketsThisTrip] were issued since the trip's [TripMark]; [remaining] can be
     * watched now; [welcome] is true while the next ticket is the unplayed welcome ticket. Card 2 never
     * promises more than can be watched, and with nothing to watch there is no card 2 at all.
     */
    fun arrival(ticketsThisTrip: Int, remaining: Int, welcome: Boolean = false): RewardPlan {
        val tickets = when {
            remaining <= 0 -> null
            ticketsThisTrip > 0 -> TicketWording("시청권", minOf(ticketsThisTrip, remaining))
            welcome -> WELCOME
            else -> TicketWording("이어서 볼 시청권", remaining)
        }
        val steps = mutableListOf(RewardStep(RewardBeat.ARRIVE, 0, ARRIVE_MS))
        if (tickets == null) {
            steps += RewardStep(RewardBeat.DISTANCE, ARRIVE_MS, ALONE_MS)
            steps += RewardStep(RewardBeat.DISMISS, ARRIVE_MS + ALONE_MS, DISMISS_MS)
        } else {
            steps += RewardStep(RewardBeat.DISTANCE, ARRIVE_MS, CARD_MS)
            steps += RewardStep(RewardBeat.TICKETS, ARRIVE_MS + CARD_MS, CARD_MS)
            steps += RewardStep(RewardBeat.DISMISS, ARRIVE_MS + 2 * CARD_MS, DISMISS_MS)
        }
        return RewardPlan(steps, tickets)
    }

    /** The welcome ticket's first playback, parked without a drive: card 2 alone, then the theater. */
    fun welcome(): RewardPlan = cardAlone(WELCOME, WELCOME_MS)

    /**
     * A charge port connected in park earned one more ticket: card 2 alone ("충전 보너스 1장"), then the
     * theater. The card says it where a toast would cover the theater's caption once the briefing opens.
     */
    fun chargeBonus(): RewardPlan = cardAlone(CHARGE_BONUS, BONUS_MS)

    private fun cardAlone(wording: TicketWording, ms: Long) = RewardPlan(
        listOf(RewardStep(RewardBeat.TICKETS, 0, ms), RewardStep(RewardBeat.DISMISS, ms, DISMISS_MS)),
        wording,
    )

    /**
     * The welcome card comes once, before the first playback of a fresh install: nothing credited yet, the
     * next ticket is the welcome ticket at its very start, and the card has not finished before ([welcomed]).
     */
    fun welcomeDue(creditedCount: Int, current: AdPlayback?, welcomed: Boolean): Boolean =
        !welcomed && creditedCount == 0 && current != null &&
            current.adId == MockPlaybackSession.WELCOME_TICKET && current.positionMs == 0L

    /**
     * The trip's mark: taken when the vehicle starts to drive and kept through lights, until the host
     * clears it at the arrival's hand-off. A cancelled arrival keeps it, so the next one counts both legs.
     */
    fun mark(current: TripMark?, vehicle: VehicleState, issuedToday: Int, today: String): TripMark? =
        current ?: if (vehicle == VehicleState.DRIVING) TripMark(issuedToday.coerceAtLeast(0), today) else null

    /** Card 1's figure: [tenths] of a kilometre as "12.3" (the meter's unit, never negative). */
    fun distanceNumber(tenths: Long): String {
        val value = tenths.coerceAtLeast(0)
        return "${value / 10}.${value % 10}"
    }

    /** Tickets issued since [mark]; across midnight today's count restarted at 0, so all of today's count. */
    fun ticketsThisTrip(mark: TripMark?, issuedToday: Int, today: String): Int = when {
        mark == null -> 0
        mark.day != today -> issuedToday.coerceAtLeast(0)
        else -> (issuedToday - mark.issued).coerceAtLeast(0)
    }
}

/**
 * Plays a [RewardPlan] on an external clock: [advanceTo] fires every beat that came due, in order and
 * once each, with the hand-off right after the dismiss beat and the end last. After [cancel] (also from
 * inside a callback) nothing fires again, so a departure mid-sequence never opens the theater.
 */
class RewardRun(val plan: RewardPlan, private val listener: Listener) {
    interface Listener {
        fun onBeat(step: RewardStep)
        /** The stage is the host's again (the dismiss starts): acknowledge, release the player. */
        fun onHandOff()
        /** Nothing of the sequence is left on screen. */
        fun onEnd()
    }

    private val events: List<Pair<Long, () -> Unit>> = buildList {
        plan.steps.sortedBy { it.startMs }.forEach { step ->
            add(step.startMs to { listener.onBeat(step) })
            if (step.beat == RewardBeat.DISMISS) add(step.startMs to { listener.onHandOff() })
        }
        add(plan.endMs to { listener.onEnd() })
    }
    private var next = 0
    private var cancelled = false

    val done: Boolean get() = cancelled || next >= events.size

    /** Time of the next event after the start, or null when the run is over or cancelled. */
    fun nextDueMs(): Long? = if (done) null else events[next].first

    fun advanceTo(elapsedMs: Long) {
        while (!done && events[next].first <= elapsedMs) {
            val fire = events[next].second
            next += 1
            fire()
        }
    }

    fun cancel() {
        cancelled = true
    }
}
