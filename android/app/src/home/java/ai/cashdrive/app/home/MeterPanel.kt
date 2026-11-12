package ai.cashdrive.app.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Colours of the meter face. The face is the one deliberately dark surface of the home in both looks
 * (design §5): a dark instrument set into the Pleos panel, amber for the live figures, grey labels.
 * Amber on the face is 9.7:1, the labels 11:1, the 70% white units 11:1.
 */
internal object MeterFace {
    const val FACE = 0xFF131417.toInt()
    const val EDGE = 0x1AFFFFFF
    /** The recessed odometer window and its drum cells. */
    const val WINDOW = 0xFF0A0B0D.toInt()
    const val CELL = 0xFF16181C.toInt()
    /** Dividers, the window's inner stroke and the unlit gauge cells. */
    const val RULE = 0xFF2A2C31.toInt()
    const val AMBER = 0xFFE0A93C.toInt()
    /** The perforation of a ready stub. */
    const val AMBER_DEEP = 0xFFB9842A.toInt()
    /** The dashed outline of a stub not issued yet (about 2.6:1 on the face). */
    const val STUB_DASH = 0xFF5C5F68.toInt()
    const val LABEL = 0xFFC7C9CE.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val UNIT = 0xB3FFFFFF.toInt()
}

/** Ease-out cubic, the one curve of the panel: fast start, soft landing. */
internal val EASE_OUT_CUBIC: TimeInterpolator = PathInterpolator(0.33f, 1f, 0.68f, 1f)

/** Runs [block] when the animator ends or is cancelled, so a cancelled animation still lands on its final state. */
internal fun ValueAnimator.whenDone(block: () -> Unit): ValueAnimator = apply {
    addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) { block() }
    })
}

/**
 * The taxi-meter panel (design §5, reviews 2026-11-09): a dark instrument card filling the column with
 * three sections and a bottom block, the card's spare height shared evenly by the gaps around the two
 * 1px rules and above the bottom block, so the instrument fills its card in every state:
 * - "이번 주행" (parked "오늘"): the odometer window (88dp drums) with "km" on its baseline, the ten-cell
 *   ticket gauge under it and "다음 시청권까지 0.7 km"; at the daily cap one line replaces gauge and distance.
 * - "시청권 1/10": today's stubs as large ticket shapes in two rows of five, with a "+N장" pill at the
 *   label's right end for tickets beyond today's issue (the narrow layout keeps one row of small stubs).
 * - The credits figure (72dp) with "크레딧" on its baseline.
 * - Bottom block: the status hint in a slot of its own (kept while empty, so a hint coming or going moves
 *   nothing) right above the context line.
 * Values animate with transform and alpha only (rolling drums, popping cells, the flying stub, a count-up
 * with one glow in park); the host decides every number and how much may move ([setMotion]).
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class MeterPanel internal constructor(context: Context, private val kit: PleosKit) : FrameLayout(context) {
    private val card = LinearLayout(context)
    private val heroLabel = line(22, MeterFace.LABEL)
    private val odometer = LinearLayout(context)
    private val digits = RollingDigits(context)
    private val unit = TextView(context)
    private val gauge = SegmentGauge(context)
    private val nextLine = line(22, MeterFace.LABEL)
    private val ticketsHeader = LinearLayout(context)
    private val ticketsLabel = line(22, MeterFace.LABEL)
    /** Wide grid only: tickets beyond today's issue as an amber "+N장" pill at the label's right end. */
    private val extraPill = TextView(context)
    private val stubs = TicketStubRow(context)
    private val creditsBox = CreditsBox(context)
    private val glow = GlowView(context)
    private val creditsRow = LinearLayout(context)
    private val creditsNumber = TextView(context)
    private val creditsUnit = TextView(context)
    private val rules = List(2) { View(context) }
    /** The flexible gaps: above and below each rule. The hint's box above the context line is the fifth. */
    private val gaps = List(4) { View(context) }
    private val hintBox = FrameLayout(context)
    private val hintText = line(22, MeterFace.TEXT)
    private val contextText = line(22, MeterFace.LABEL)
    private val flyer = StubGlyphView(context)
    private var level = MotionLevel.FULL
    private var compact = false
    private var model: MeterModel? = null
    private var contextLine: String? = null
    private var hint: String? = null
    private var heroKey: String? = null
    private var nextKey: String? = null
    private var ticketsKey: String? = null
    private var creditsTarget = -1
    private var shownCredits = 0
    private var creditsAnimator: ValueAnimator? = null
    private var glowAnimator: ValueAnimator? = null
    private var flyAnimator: ValueAnimator? = null
    private var flight = 0
    private var flightPending = false
    private val point = FloatArray(2)
    private val rect = Rect()

    init {
        clipChildren = false
        card.orientation = LinearLayout.VERTICAL
        card.clipChildren = false
        card.background = GradientDrawable().apply { setColor(MeterFace.FACE); cornerRadius = dp(24).toFloat(); setStroke(1, MeterFace.EDGE) }
        // The glow (and a settling stub's tilted corner) may spread past its row into the card's padding,
        // but never past the card's rounded face.
        card.clipToPadding = false
        card.clipToOutline = true
        addView(card, LayoutParams(-1, -1))

        // Section 1: the odometer window with the unit on the digits' baseline, the gauge and the distance line.
        card.addView(heroLabel)
        odometer.orientation = LinearLayout.HORIZONTAL
        odometer.isBaselineAligned = true
        odometer.addView(digits, LinearLayout.LayoutParams(-2, -2))
        unit.setTextColor(MeterFace.UNIT); unit.text = "km"; unit.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        odometer.addView(unit, LinearLayout.LayoutParams(-2, -2))
        card.addView(odometer)
        card.addView(gauge)
        card.addView(nextLine)
        card.addView(gaps[0])
        card.addView(rules[0])
        card.addView(gaps[1])

        // Section 2: today's stubs, the label row carrying the "+N장" pill in the wide grid.
        ticketsHeader.orientation = LinearLayout.HORIZONTAL
        ticketsHeader.gravity = Gravity.CENTER_VERTICAL
        ticketsHeader.addView(ticketsLabel, LinearLayout.LayoutParams(0, -2, 1f))
        kit.style(extraPill, 20, bold = true)
        extraPill.setTextColor(MeterFace.AMBER); extraPill.fontFeatureSettings = "tnum"
        extraPill.includeFontPadding = false
        extraPill.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(dp(2), MeterFace.AMBER) }
        extraPill.setPadding(dp(14), dp(6), dp(14), dp(6))
        extraPill.visibility = View.GONE
        ticketsHeader.addView(extraPill, LinearLayout.LayoutParams(-2, -2))
        card.addView(ticketsHeader)
        card.addView(stubs)
        card.addView(gaps[2])
        card.addView(rules[1])
        card.addView(gaps[3])

        // Section 3: the credits figure; the glow fills the row's box and spreads past it when it plays.
        creditsBox.clipChildren = false
        glow.alpha = 0f
        creditsBox.addView(glow, LayoutParams(-1, -1))
        creditsRow.orientation = LinearLayout.HORIZONTAL
        creditsRow.isBaselineAligned = true
        creditsNumber.setTextColor(MeterFace.AMBER); creditsNumber.fontFeatureSettings = "tnum"; creditsNumber.letterSpacing = -0.01f
        creditsNumber.maxLines = 1
        // Right-aligned in a box as wide as the final figure: during a count-up the gap to "크레딧" stays 10dp
        // and a new digit appears on the left, like a drum.
        creditsNumber.gravity = Gravity.END
        creditsRow.addView(creditsNumber, LinearLayout.LayoutParams(-2, -2))
        creditsUnit.setTextColor(MeterFace.UNIT); creditsUnit.text = "크레딧"; creditsUnit.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        creditsRow.addView(creditsUnit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        creditsBox.addView(creditsRow, LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        card.addView(creditsBox)

        // Bottom block: the hint at the foot of its flexible box, then the context line on the card's bottom edge.
        hintText.maxLines = 2
        hintBox.addView(hintText, LayoutParams(-1, -2, Gravity.BOTTOM))
        card.addView(hintBox)
        card.addView(contextText)
        // Korean breaks between words, not inside one ("쌓여요" never splits across lines).
        if (Build.VERSION.SDK_INT >= 33) listOf(hintText, contextText).forEach { it.lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE }

        flyer.visibility = View.GONE
        addView(flyer, LayoutParams(dp(44), dp(30)))

        rules.forEach { it.setBackgroundColor(MeterFace.RULE) }
        applyCompact()
    }

    /** The face keeps its own colours in both looks; the tokens are accepted for the host's uniform theming call. */
    fun setTokens(@Suppress("UNUSED_PARAMETER") tokens: PleosTokens) = Unit

    /** Sets how much the panel may move. Takes effect from the next [bind]; a running animation finishes. */
    fun setMotion(level: MotionLevel) {
        this.level = level
        digits.durationScale = level.scale
        gauge.durationScale = level.scale
        stubs.durationScale = level.scale
    }

    /** Narrow layout (design §13-9): smaller drums and credits, tighter rhythm, no hint or context line. */
    fun setCompact(value: Boolean) {
        if (value == compact) return
        compact = value
        applyCompact()
    }

    /**
     * Shows [model]. With [animate] (and a motion level above NONE) changed figures roll, pop and count
     * up; without it every value snaps and running animations end on their final state first.
     */
    fun bind(model: MeterModel, animate: Boolean) {
        val motion = animate && level != MotionLevel.NONE
        if (!motion) cancelFlyer()
        val meters = if (model.parked) model.todayMeters else model.tripMeters
        val hero = if (model.parked) "오늘" else "이번 주행"
        if (hero != heroKey) { heroKey = hero; heroLabel.text = hero }
        val tenths = MeterMath.tenths(meters)
        digits.setValue(tenths, motion)
        digits.contentDescription = "$hero ${tenths / 10}.${tenths % 10} km"
        val capped = MeterMath.capped(model.issuedToday, model.dailyCap)
        gauge.visibility = if (capped) View.GONE else View.VISIBLE
        if (!capped) gauge.setFilled(MeterMath.gaugeFraction(model.nextTicketMeters), motion)
        val next = if (capped) CAPPED else MeterMath.distanceLabel(model.nextTicketMeters)
        if (next != nextKey) {
            nextKey = next
            nextLine.text = if (capped) emphasis("", CAPPED) else emphasis("다음 시청권까지 ", next)
        }
        val stubLayout = StubRow.layout(model.issuedToday, model.remaining, model.dailyCap)
        stubs.set(stubLayout, motion)
        val extra = stubLayout.extra
        extraPill.visibility = if (!compact && extra > 0) View.VISIBLE else View.GONE
        if (extra > 0) {
            val label = "+${extra}장"
            if (extraPill.text.toString() != label) extraPill.text = label
            extraPill.contentDescription = "추가 시청권 ${extra}장"
        }
        val tickets = "${model.issuedToday.coerceIn(0, model.dailyCap.coerceAtLeast(0))}/${model.dailyCap}"
        if (tickets != ticketsKey) { ticketsKey = tickets; ticketsLabel.text = emphasis("시청권 ", tickets) }
        setCredits(model.credits, motion)
        setContextLine(model.contextLine)
        setHint(model.hint)
        this.model = model
    }

    /** One line of vehicle or navigation context; null hides it. Text only, so it never disturbs a running animation. */
    fun setContextLine(text: String?) {
        contextLine = text
        if (contextText.text.toString() != (text ?: "")) contextText.text = text ?: ""
        contextText.visibility = if (compact || text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * The status hint above the context line; null hides it. Text only. Its box keeps its height either way,
     * so a hint coming or going (park, a light, the theater) never moves the figures above it.
     */
    fun setHint(text: String?) {
        hint = text
        if (hintText.text.toString() != (text ?: "")) hintText.text = text ?: ""
        hintText.visibility = when {
            compact -> View.GONE
            text.isNullOrBlank() -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    /**
     * A ticket was just issued: a stub rises from the end of the gauge and flies to its place in the stub
     * row (400 ms, quadratic arc, ease-out cubic), where it settles from -6 degrees. Call after [bind]
     * with the new model; half length while driving, nothing when the panel may not move.
     */
    fun playTicketEarned() {
        val current = model ?: return
        val duration = (FLY_MS * level.scale).toLong()
        if (duration <= 0L || !isAttachedToWindow) return
        val layout = StubRow.layout(current.issuedToday, current.remaining, current.dailyCap)
        if (layout.ready <= 0) return
        cancelFlyer()
        stubs.holdNewest(true)
        val token = flight
        flightPending = true
        // The bind before this call may collapse the gauge (the cap) and move the row: measure the path
        // only once this frame's layout is done. A cancel in between (a snapping bind) drops the flight.
        afterLayout {
            if (token == flight) { flightPending = false; fly(layout.punched + layout.ready - 1, duration) }
        }
    }

    private fun fly(newest: Int, duration: Long) {
        if (gauge.visibility == View.VISIBLE) {
            gauge.centerOf(MeterMath.SEGMENTS - 1, point); toMyCoords(gauge, point)
        } else {
            // At the cap the stub rises from the line that replaced the gauge.
            point[0] = stubs.cellWidth / 2f; point[1] = nextLine.height / 2f; toMyCoords(nextLine, point)
        }
        val startX = point[0]; val startY = point[1]
        stubs.slotCenter(newest, point); toMyCoords(stubs, point)
        val endX = point[0]; val endY = point[1]
        val w = stubs.cellWidth.toInt().coerceAtLeast(dp(8)); val h = stubs.cellHeight.toInt().coerceAtLeast(dp(6))
        flyer.layoutParams = LayoutParams(w, h)
        flyer.rotation = -6f; flyer.alpha = 0f; flyer.scaleX = 0.8f; flyer.scaleY = 0.8f
        flyer.translationX = startX - w / 2f; flyer.translationY = startY - h / 2f
        flyer.visibility = View.VISIBLE
        // The control point sits above and right of the straight line, so the stub lifts before it dives.
        val controlX = (startX + endX) / 2f + dp(24)
        val controlY = minOf(startY, endY) - dp(36)
        flyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = EASE_OUT_CUBIC
            addUpdateListener {
                val t = it.animatedValue as Float
                val u = 1f - t
                val x = u * u * startX + 2f * u * t * controlX + t * t * endX
                val y = u * u * startY + 2f * u * t * controlY + t * t * endY
                flyer.translationX = x - w / 2f; flyer.translationY = y - h / 2f
                flyer.alpha = (t * 4f).coerceAtMost(1f)
                val s = 0.8f + 0.2f * t
                flyer.scaleX = s; flyer.scaleY = s
            }
            whenDone {
                flyAnimator = null
                flyer.visibility = View.GONE
                stubs.holdNewest(false)
                stubs.settleNewest()
            }
            start()
        }
    }

    /** Runs [block] now when the panel is laid out and no layout is pending, otherwise right before the next draw. */
    private fun afterLayout(block: () -> Unit) {
        if (isLaidOut && !isLayoutRequested) { block(); return }
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
                block()
                return true
            }
        })
    }

    /** One blink of the ready stubs, for the arrival sequence's "ticket stamped" beat. */
    fun blinkStubs() {
        if (level != MotionLevel.NONE) stubs.blink()
    }

    /**
     * The credits figure. In park a rise counts up for 600 ms with one amber glow behind the number;
     * anything else (driving, reduce motion, a drop, the first bind) snaps. The number's box is fixed to
     * the final figure's width, so the count-up only redraws and "크레딧" never shifts.
     */
    private fun setCredits(value: Int, animate: Boolean) {
        if (value == creditsTarget) {
            if (!animate) { creditsAnimator?.cancel(); glowAnimator?.cancel() }
            return
        }
        creditsAnimator?.cancel(); glowAnimator?.cancel()
        val from = if (creditsTarget < 0) value else shownCredits
        creditsTarget = value
        creditsNumber.contentDescription = "크레딧 ${format(value)}"
        val duration = (COUNT_MS * level.scale).toLong()
        if (!animate || duration <= 0L || !isAttachedToWindow || !MeterMath.creditsAnimate(level, from, value)) {
            showCredits(value)
            return
        }
        fitCreditsWidth(format(maxOf(from, value)))
        // Rounded, not truncated: the last figure lands at about 63% of the run instead of on the final frame.
        creditsAnimator = ValueAnimator.ofFloat(from.toFloat(), value.toFloat()).apply {
            this.duration = duration
            interpolator = EASE_OUT_CUBIC
            addUpdateListener {
                val shown = (it.animatedValue as Float).roundToInt()
                if (shown != shownCredits) { shownCredits = shown; creditsNumber.text = format(shown) }
            }
            whenDone { creditsAnimator = null; showCredits(creditsTarget) }
            start()
        }
        playGlow(duration)
    }

    private fun showCredits(value: Int) {
        shownCredits = value
        val text = format(value)
        fitCreditsWidth(text)
        if (creditsNumber.text.toString() != text) creditsNumber.text = text
    }

    private fun fitCreditsWidth(text: String) {
        // Two pixels of slack: the text layout may round a hair wider than the paint measures.
        val width = ceil(creditsNumber.paint.measureText(text)).toInt() + 2 + creditsNumber.paddingLeft + creditsNumber.paddingRight
        val params = creditsNumber.layoutParams
        if (params.width != width) { params.width = width; creditsNumber.layoutParams = params }
    }

    /**
     * One amber bloom behind the credits, parked only: the alpha rises to 0.25 in the first 18% and fades
     * out over the rest (smoothstep) while the bloom grows from 0.85 to 1.15, so it reads as one glow
     * rather than a flash. Alpha and scale of its own view only.
     */
    private fun playGlow(duration: Long) {
        glowAnimator?.cancel()
        if (!creditsNumber.isLaidOut) return
        val cx = creditsRow.left + creditsNumber.left + creditsNumber.width / 2f
        val cy = creditsRow.top + creditsNumber.top + creditsNumber.height / 2f
        glow.place(cx, cy, creditsNumber.width / 2f + dp(56), creditsNumber.height / 2f + dp(28))
        glowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                glow.alpha = GLOW_PEAK * if (t < GLOW_RISE) {
                    EASE_OUT_CUBIC.getInterpolation(t / GLOW_RISE)
                } else {
                    val u = (t - GLOW_RISE) / (1f - GLOW_RISE)
                    1f - u * u * (3f - 2f * u)
                }
                val s = 0.85f + 0.3f * EASE_OUT_CUBIC.getInterpolation(t)
                glow.scaleX = s; glow.scaleY = s
            }
            whenDone { glowAnimator = null; glow.alpha = 0f }
            start()
        }
    }

    /** Ends a flight (pending or running): the held stub shows again in place, nothing is left mid-air. */
    private fun cancelFlyer() {
        if (flyAnimator == null && !flightPending) return
        flight += 1
        flightPending = false
        val running = flyAnimator
        flyAnimator = null
        running?.cancel()
        flyer.visibility = View.GONE
        stubs.holdNewest(false)
    }

    /**
     * The rhythm in dp (wide / compact): 28/20 card padding; label to content 12/8; window to gauge
     * 20/12; gauge 12/10 high; gauge to line 12/8; stubs 32/26 high (30dp tickets with room for their
     * outline). Five flexible gaps share the card's spare height evenly: above and below each 1px rule (at
     * least 20/12 each) and the hint's box above the context line (8dp over it), whose height is its share
     * alone, so the band over the context line without a hint is as tall as the others and a hint sits at
     * its foot (a share is far taller than a hint line on any wide card). The hint and the context line show
     * only in the wide layout.
     */
    private fun applyCompact() {
        val c = compact
        val pad = dp(if (c) 20 else 28)
        card.setPadding(pad, pad, pad, pad)
        digits.setTextSizeDp(if (c) 48 else 88)
        kit.style(unit, if (c) 22 else 28)
        kit.style(creditsNumber, if (c) 40 else 72, bold = true)
        kit.style(creditsUnit, if (c) 20 else 26)
        heroLabel.layoutParams = LinearLayout.LayoutParams(-1, -2)
        odometer.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (c) 8 else 12) }
        (unit.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(if (c) 10 else 12)
        gauge.layoutParams = LinearLayout.LayoutParams(-1, dp(if (c) 10 else 12)).apply { topMargin = dp(if (c) 12 else 20) }
        nextLine.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (c) 8 else 12) }
        val gap = dp(if (c) 12 else 20)
        gaps.forEach { it.layoutParams = LinearLayout.LayoutParams(-1, gap, 1f) }
        rules.forEach { it.layoutParams = LinearLayout.LayoutParams(-1, 1) }
        ticketsHeader.layoutParams = LinearLayout.LayoutParams(-1, -2)
        // Narrow: one row of small stubs with the "+N" pill drawn after it. Wide: rows of five large stubs, the
        // view measuring its own height from the width. Either way the view is 2dp taller than the tickets
        // (outline room), so its margins give 1dp back on each side.
        stubs.perRow = if (c) 0 else WIDE_STUBS_PER_ROW
        stubs.layoutParams = LinearLayout.LayoutParams(-1, if (c) dp(26) else -2).apply { topMargin = dp(if (c) 7 else 15); bottomMargin = -dp(1) }
        model?.let { extraPill.visibility = if (!c && StubRow.layout(it.issuedToday, it.remaining, it.dailyCap).extra > 0) View.VISIBLE else View.GONE }
        creditsBox.layoutParams = LinearLayout.LayoutParams(-1, -2)
        // The hint's box is the fifth gap: its share of the spare height; a hint at its foot may reach past
        // its top rather than be cut (the card does not clip its children).
        hintBox.clipChildren = false
        hintBox.layoutParams = LinearLayout.LayoutParams(-1, if (c) gap else 0, 1f)
        contextText.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (c) 0 else dp(8) }
        creditsTarget.takeIf { it >= 0 }?.let { fitCreditsWidth(format(it)) }
        setContextLine(contextLine)
        setHint(hint)
        requestLayout()
    }

    /** Converts a point in [child]'s coordinates to this panel's. */
    private fun toMyCoords(child: View, xy: FloatArray) {
        rect.set(xy[0].toInt(), xy[1].toInt(), xy[0].toInt(), xy[1].toInt())
        offsetDescendantRectToMyCoords(child, rect)
        xy[0] = rect.left.toFloat(); xy[1] = rect.top.toFloat()
    }

    /** "[lead][value]": the lead in the label grey, the value in white tabular figures. */
    private fun emphasis(lead: String, value: String): CharSequence = SpannableStringBuilder(lead).apply {
        val start = length
        append(value)
        setSpan(ForegroundColorSpan(MeterFace.TEXT), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun line(size: Int, color: Int) = kit.text("", size, color).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum"
    }

    private fun dp(value: Int) = kit.dp(value)
    private fun format(value: Int) = "%,d".format(value)

    override fun onDetachedFromWindow() {
        creditsAnimator?.cancel(); glowAnimator?.cancel(); cancelFlyer()
        super.onDetachedFromWindow()
    }

    /**
     * The credits row's box: as big as the row, never as big as the glow. A match_parent child of a
     * wrap_content FrameLayout would first measure at the full height left in the card, so the glow
     * (child 0) is measured only after the row (child 1) has fixed the size.
     */
    private class CreditsBox(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val glowView = getChildAt(0)
            val row = getChildAt(1)
            measureChildWithMargins(row, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val width = resolveSize(row.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec)
            val height = resolveSize(row.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec)
            glowView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            setMeasuredDimension(width, height)
        }
    }

    /** An amber ellipse that fades to clear, centred on the credits figure; drawn past its own box on purpose. */
    private class GlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cx = 0f
        private var cy = 0f
        private var rx = 0f
        private var ry = 0f

        fun place(x: Float, y: Float, radiusX: Float, radiusY: Float) {
            cx = x; cy = y; rx = radiusX; ry = radiusY.coerceAtLeast(1f)
            // A full core that softens to clear, so the bloom has a body and no visible edge.
            paint.shader = RadialGradient(x, y, ry, intArrayOf(MeterFace.AMBER, (MeterFace.AMBER and 0x00FFFFFF) or 0x8C000000.toInt(), MeterFace.AMBER and 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            pivotX = x; pivotY = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (rx <= 0f) return
            canvas.save()
            canvas.scale(rx / ry, 1f, cx, cy)
            canvas.drawCircle(cx, cy, ry, paint)
            canvas.restore()
        }

        /**
         * One shape, nothing overlaps: the alpha applies to the draw itself. With the default (true) a
         * view below full alpha renders through an offscreen layer the size of its bounds, which cut the
         * bloom into a hard-edged box the size of the credits row.
         */
        override fun hasOverlappingRendering() = false
    }

    private companion object {
        private const val COUNT_MS = 600L
        private const val FLY_MS = 400L
        /** The glow's peak alpha (design §5: 0.25 to 0) and the share of its run spent rising to it. */
        private const val GLOW_PEAK = 0.25f
        private const val GLOW_RISE = 0.18f
        private const val CAPPED = "오늘 시청권을 모두 받았어요"
        /** The wide meter shows today's ten tickets as two rows of five. */
        private const val WIDE_STUBS_PER_ROW = 5
    }
}
