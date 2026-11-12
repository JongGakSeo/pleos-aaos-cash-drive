package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The distance as an odometer window (design §5, panel review 2026-11-09): a recessed rounded window
 * (16dp corners, 1px inner stroke, 12dp padding) holding one drum cell per digit (8dp corners, 6dp
 * apart), three integer drums and the tenths drum like a car's trip meter, with the decimal point
 * between the last two cells. Leading zero drums stay dim so the figure reads at a glance.
 *
 * A change rolls every drum that differs: the old digit leaves through the cell edge and the new one
 * follows it in, upward when the value grows (280 ms ease-out cubic, translation only, clipped to the
 * cell). A shade at the top and bottom of every cell turns a rolling digit away like a real drum.
 * Unchanged drums stay still. Every size follows the digit size (64dp; 48dp in the compact layout).
 * Values only: the host decides the distance.
 */
@SuppressLint("ViewConstructor")
class RollingDigits(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.CENTER
        color = MeterFace.AMBER
    }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MeterFace.WINDOW }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MeterFace.RULE; strokeWidth = 1f }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MeterFace.CELL }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MeterFace.AMBER }
    private val rect = RectF()
    private val glyph = Rect()

    /** Roll length multiplier from the panel's motion level: 1 parked, 0.5 driving, 0 snaps. */
    var durationScale = 1f

    private var sizeDp = 64
    private var target = -1L
    private var drums = MeterMath.drums(0).toIntArray()
    private var outgoing = drums
    private var lit = MeterMath.firstLit(drums.asList())
    private var outgoingLit = lit
    private var roll = 1f
    private var upward = true
    private var animator: ValueAnimator? = null

    // Geometry in px, all derived from the digit size in measureFont().
    private var pad = 0f
    private var gap = 0f
    private var cellW = 0f
    private var cellH = 0f
    private var cellR = 0f
    private var windowR = 0f
    private var dotR = 0f
    private var dotGap = 0f
    private var restBaseline = 0f

    init {
        measureFont()
        contentDescription = describe(0)
    }

    fun setTextSizeDp(dp: Int) {
        if (dp == sizeDp) return
        sizeDp = dp
        measureFont()
        requestLayout()
        invalidate()
    }

    /**
     * Shows [tenths] (0.1 km units). With [animate] false, or a zero duration, the drums snap; a running
     * roll always ends on its final digits first, so a rebind never leaves a half-turned drum.
     */
    fun setValue(tenths: Long, animate: Boolean) {
        val value = tenths.coerceAtLeast(0)
        if (value == target) {
            if (!animate) animator?.cancel()
            return
        }
        animator?.cancel()
        val first = target < 0
        upward = value > target
        target = value
        contentDescription = describe(value)
        val next = MeterMath.drums(value).toIntArray()
        val duration = (ROLL_MS * durationScale).toLong()
        if (first || !animate || duration <= 0L || !isAttachedToWindow) {
            snap(next)
            return
        }
        val size = maxOf(drums.size, next.size)
        val relayout = size != drums.size
        outgoing = padded(drums, size)
        outgoingLit = MeterMath.firstLit(outgoing.asList())
        drums = padded(next, size)
        lit = MeterMath.firstLit(drums.asList())
        roll = 0f
        if (relayout) requestLayout()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { roll = it.animatedValue as Float; invalidate() }
            whenDone { animator = null; snap(MeterMath.drums(target).toIntArray()) }
            start()
        }
    }

    private fun snap(next: IntArray) {
        val relayout = next.size != drums.size
        drums = next
        outgoing = next
        lit = MeterMath.firstLit(next.asList())
        outgoingLit = lit
        roll = 1f
        if (relayout) requestLayout()
        invalidate()
    }

    /** Leading zeros up to [size] drums, so a value that gains a drum rolls it in from a dim zero. */
    private fun padded(digits: IntArray, size: Int) = IntArray(size) { i ->
        val j = i - (size - digits.size)
        if (j >= 0) digits[j] else 0
    }

    /** The review's sizes at a 64dp digit (12dp padding, 6dp gaps, 8dp and 16dp corners), scaled with the digit. */
    private fun measureFont() {
        val k = sizeDp / 64f * density
        digitPaint.textSize = sizeDp * density
        digitPaint.getTextBounds("0", 0, 1, glyph)
        val glyphH = (glyph.bottom - glyph.top).toFloat()
        pad = 12 * k
        gap = 6 * k
        cellR = 8 * k
        windowR = 16 * k
        cellW = digitPaint.measureText("0") + 20 * k
        cellH = glyphH + 30 * k
        restBaseline = (cellH - glyphH) / 2f - glyph.top
        dotR = 0.07f * digitPaint.textSize
        dotGap = 2 * gap + 2 * dotR
        // The drum shade: dark at the cell's top and bottom edges, clear over the resting digit.
        shadePaint.shader = LinearGradient(0f, 0f, 0f, cellH, intArrayOf(SHADE, 0, 0, SHADE),
            floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
    }

    private fun contentWidth(count: Int) = count * cellW + (count - 2).coerceAtLeast(0) * gap + dotGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paddingLeft + paddingRight + ceil(2 * pad + contentWidth(drums.size)).toInt()
        val h = paddingTop + paddingBottom + ceil(2 * pad + cellH).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    /** Baseline of a resting digit, so the "km" next to the window sits on the same line in a LinearLayout. */
    override fun getBaseline(): Int = paddingTop + (pad + restBaseline).roundToInt()

    override fun onDraw(canvas: Canvas) {
        val left0 = paddingLeft.toFloat()
        val top0 = paddingTop.toFloat()
        val count = drums.size
        // The recessed window and its 1px inner stroke.
        rect.set(left0, top0, left0 + 2 * pad + contentWidth(count), top0 + 2 * pad + cellH)
        canvas.drawRoundRect(rect, windowR, windowR, windowPaint)
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, windowR - 0.5f, windowR - 0.5f, bezelPaint)
        val top = top0 + pad
        var x = left0 + pad
        for (i in 0 until count) {
            if (i == count - 1) {
                // The decimal point sits on the digits' baseline in the wider gap before the tenths drum.
                canvas.drawCircle(x - gap + dotGap / 2f, top + restBaseline - dotR, dotR, dotPaint)
                x += dotGap - gap
            }
            drawDrum(canvas, i, x, top)
            x += cellW + gap
        }
    }

    private fun drawDrum(canvas: Canvas, i: Int, left: Float, top: Float) {
        rect.set(left, top, left + cellW, top + cellH)
        canvas.drawRoundRect(rect, cellR, cellR, cellPaint)
        canvas.save()
        canvas.clipRect(rect)
        val cx = left + cellW / 2f
        val base = top + restBaseline
        val current = drums[i]
        val previous = outgoing.getOrElse(i) { current }
        val inAlpha = if (i >= lit) 255 else DIM_ALPHA
        val outAlpha = if (i >= outgoingLit) 255 else DIM_ALPHA
        if (roll >= 1f || current == previous) {
            // A still drum; a leading zero that lights up or dims without turning crossfades instead.
            digitPaint.alpha = if (roll >= 1f) inAlpha else (outAlpha + (inAlpha - outAlpha) * roll).roundToInt()
            drawDigit(canvas, current, cx, base)
        } else {
            val direction = if (upward) -1f else 1f
            val shift = cellH * roll
            digitPaint.alpha = outAlpha
            drawDigit(canvas, previous, cx, base + direction * shift)
            digitPaint.alpha = inAlpha
            drawDigit(canvas, current, cx, base + direction * (shift - cellH))
        }
        digitPaint.alpha = 255
        canvas.restore()
        canvas.save()
        canvas.translate(left, top)
        rect.set(0f, 0f, cellW, cellH)
        canvas.drawRoundRect(rect, cellR, cellR, shadePaint)
        canvas.restore()
    }

    private fun drawDigit(canvas: Canvas, digit: Int, x: Float, baseline: Float) {
        canvas.drawText(DIGITS, digit, digit + 1, x, baseline, digitPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun describe(tenths: Long) = "${tenths / 10}.${tenths % 10} km"

    private companion object {
        const val ROLL_MS = 280L
        const val DIGITS = "0123456789"
        /** Leading zeros: amber at 25%, an unlit drum rather than a figure. */
        const val DIM_ALPHA = 64
        /** Black at 60% on the cell edge: a drum turning away from the window. */
        const val SHADE = 0x99000000.toInt()
    }
}
