package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * The distance to the next ticket as ten cells, one per 100 m of the 1 km block (design §5: 4dp apart,
 * lit amber, unlit #44464E, about 2:1 on the face so the empty gauge still reads; 3dp corners; the view's
 * height is the cell height, 12dp wide and 10dp compact). A cell that lights up pops in (scale 0.95 to 1
 * with a fade, 280 ms ease-out cubic); cells going dark fade out in place. Values only: the host supplies
 * the fraction.
 */
@SuppressLint("ViewConstructor")
class SegmentGauge(context: Context) : View(context) {
    /** Pop length multiplier from the panel's motion level: 1 parked, 0.5 driving, 0 snaps. */
    var durationScale = 1f

    private var lit = 0
    private val changing = BooleanArray(MeterMath.SEGMENTS)
    private var t = 1f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    init {
        contentDescription = "0/${MeterMath.SEGMENTS}"
    }

    /** Lights the cells for [fraction] (0..1 of the block). Without [animate] it snaps and ends a running pop. */
    fun setFilled(fraction: Float, animate: Boolean) {
        val next = MeterMath.litSegments(fraction)
        if (next == lit) {
            if (!animate) animator?.cancel()
            return
        }
        animator?.cancel()
        val previous = lit
        lit = next
        contentDescription = "$lit/${MeterMath.SEGMENTS}"
        val duration = (POP_MS * durationScale).toLong()
        if (!animate || duration <= 0L || !isAttachedToWindow) {
            invalidate()
            return
        }
        for (i in 0 until MeterMath.SEGMENTS) changing[i] = (i < previous) != (i < next)
        t = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            whenDone { animator = null; t = 1f; changing.fill(false); invalidate() }
            start()
        }
    }

    /** Centre of cell [index] in this view's coordinates (the flying stub rises from the last cell). */
    fun centerOf(index: Int, out: FloatArray) {
        val w = cellWidth()
        out[0] = paddingLeft + index * (w + gap()) + w / 2f
        out[1] = paddingTop + (height - paddingTop - paddingBottom) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = cellWidth()
        if (w <= 0f) return
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val radius = 3f * density
        val gap = gap()
        for (i in 0 until MeterMath.SEGMENTS) {
            val left = paddingLeft + i * (w + gap)
            rect.set(left, top, left + w, bottom)
            paint.color = UNLIT
            canvas.drawRoundRect(rect, radius, radius, paint)
            val on = i < lit
            if (!changing[i]) {
                if (on) { paint.color = MeterFace.AMBER; canvas.drawRoundRect(rect, radius, radius, paint) }
                continue
            }
            // Turning on grows and fades in; turning off fades out in place.
            val p = if (on) t else 1f - t
            val scale = if (on) 0.95f + 0.05f * p else 1f
            paint.color = MeterFace.AMBER
            paint.alpha = (255 * p).roundToInt()
            canvas.save()
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
            paint.alpha = 255
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun gap() = 4f * density
    private fun cellWidth() = ((width - paddingLeft - paddingRight) - gap() * (MeterMath.SEGMENTS - 1)) / MeterMath.SEGMENTS

    private companion object {
        const val POP_MS = 280L
        /** An unlit cell (design §5). */
        const val UNLIT = 0xFF44464E.toInt()
    }
}
