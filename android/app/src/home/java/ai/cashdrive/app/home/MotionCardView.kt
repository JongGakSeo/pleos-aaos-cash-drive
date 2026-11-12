package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.LinearInterpolator
import ai.cashdrive.app.R
import java.util.Locale

/**
 * One card of a self-made briefing on the theater's stage (design §6): a 160dp white icon over one
 * sentence (40dp bold, at most two centred lines) on a card with a faint diagonal gradient (#0E0F11 to
 * #16181D), a soft light behind the icon and a hairline edge. A new card enters with its icon scaling
 * from 0.95 to 1 while fading in (240 ms) and its sentence rising 16dp while fading in (280 ms, 40 ms
 * behind the icon); the closing basis card draws its check mark instead, the ring and then the tick as
 * one 400 ms stroke. Ease-out cubic throughout, transform and alpha only. The cards are a self-made
 * video's stand-in when its clip cannot be shown; [showUnavailable] is the stand-in for a video without
 * cards. Rendering only: the host picks the briefing and the position, the session owns safety and rewards.
 */
@SuppressLint("ViewConstructor")
class MotionCardView(context: Context) : View(context) {
    /** False (reduce motion): every card appears in its final state at once. */
    var animated = true
        set(value) {
            field = value
            if (!value) animator?.cancel()
        }

    private val density = resources.displayMetrics.density
    private var key: Any? = null
    private var icon: Drawable? = null
    private var check = false
    private var line = ""
    private var lineLayout: StaticLayout? = null
    private var elapsed = ENTRANCE_MS.toFloat()
    private var animator: ValueAnimator? = null
    private val card = RectF()
    private val iconRect = RectF()
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = EDGE }
    // Korean locale on the paint so phrase-based wrapping (API 33+) applies whatever the system locale is.
    private val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = dp(40f); textLocale = Locale.KOREA
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    // The check mark of ic_brief_check on its 24-unit grid: the ring from 12 o'clock clockwise, then the tick.
    private val ringUnits = Path().apply { addArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), -90f, 359.9f) }
    private val tickUnits = Path().apply { moveTo(7.5f, 12.5f); lineTo(10.5f, 15.5f); lineTo(16.5f, 8.5f) }
    private val ring = Path()
    private val tick = Path()
    private val ringMeasure = PathMeasure()
    private val tickMeasure = PathMeasure()
    private val part = Path()
    private val toIcon = Matrix()
    private var faceSize = 0L
    private var haloX = 0f
    private var haloY = 0f
    private var haloR = 0f

    /** Shows the card of [briefing] on screen at [positionMs] (the briefing's 15-second layout). */
    fun show(briefing: Briefing, positionMs: Long, delayMs: Long = 0L) {
        val next = BriefingCatalog.cardAt(briefing, positionMs)
        present(briefing.id to next, next.icon, next.icon == R.drawable.ic_brief_check, next.line, delayMs)
    }

    /** The stand-in for a video creative that cannot be played: the crossed camera and one sentence. */
    fun showUnavailable(delayMs: Long = 0L) {
        present(UNAVAILABLE, R.drawable.ic_player_unavailable, false, UNAVAILABLE, delayMs)
    }

    /** Forgets the card on screen, so the next [show] enters again (the theater opened anew). */
    fun reset() {
        key = null
        animator?.cancel()
    }

    private fun present(key: Any, iconRes: Int, check: Boolean, line: String, delayMs: Long) {
        if (key == this.key) return
        this.key = key
        this.check = check
        icon = if (check) null else context.getDrawable(iconRes)?.mutate()?.apply { setTint(Color.WHITE) }
        if (line != this.line) { this.line = line; lineLayout = null }
        contentDescription = line
        animator?.cancel()
        if (!animated || !isAttachedToWindow) {
            elapsed = ENTRANCE_MS.toFloat()
            invalidate()
            return
        }
        elapsed = 0f
        animator = ValueAnimator.ofFloat(0f, ENTRANCE_MS.toFloat()).apply {
            duration = ENTRANCE_MS
            startDelay = delayMs
            interpolator = LinearInterpolator()
            addUpdateListener { elapsed = it.animatedValue as Float; invalidate() }
            // Ends (or is cancelled) on the final state.
            whenDone { animator = null; elapsed = ENTRANCE_MS.toFloat(); invalidate() }
            start()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lineLayout = null
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        card.set(0f, 0f, width.toFloat(), height.toFloat())
        val radius = dp(20f)
        val size = (width.toLong() shl 32) or height.toLong()
        if (size != faceSize) {
            faceSize = size
            face.shader = LinearGradient(0f, 0f, card.right, card.bottom, FACE_FROM, FACE_TO, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(card, radius, radius, face)
        if (key == null) {
            drawEdge(canvas, radius)
            return
        }

        // Content block: the icon, a 40dp gap and the sentence, sitting a little above the optical centre.
        val pad = if (width < dp(720f)) dp(40f) else dp(64f)
        val textWidth = (width - 2 * pad).toInt().coerceAtLeast(1)
        val layout = lineLayout?.takeIf { it.width == textWidth } ?: buildLine(line, textWidth).also { lineLayout = it }
        val gap = dp(40f)
        val iconSize = minOf(dp(160f), height - 2 * dp(32f) - gap - layout.height).coerceAtLeast(dp(64f))
        val block = iconSize + gap + layout.height
        val top = ((height - block) * 0.46f).coerceAtLeast(0f)
        val cx = width / 2f
        iconRect.set(cx - iconSize / 2f, top, cx + iconSize / 2f, top + iconSize)

        // A soft light behind the icon, part of the card face (it does not animate).
        val haloRadius = iconSize * 1.25f
        if (cx != haloX || iconRect.centerY() != haloY || haloRadius != haloR) {
            haloX = cx; haloY = iconRect.centerY(); haloR = haloRadius
            halo.shader = RadialGradient(haloX, haloY, haloR, HALO, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(haloX, haloY, haloR, halo)
        drawEdge(canvas, radius)

        if (check) drawCheck(canvas, iconSize) else drawIcon(canvas)

        val lineP = eased(elapsed - LINE_DELAY_MS, LINE_MS)
        if (lineP > 0f) {
            linePaint.alpha = (255 * lineP).toInt()
            canvas.save()
            canvas.translate(pad, iconRect.bottom + gap + dp(16f) * (1f - lineP))
            layout.draw(canvas)
            canvas.restore()
            linePaint.alpha = 255
        }
    }

    private fun drawEdge(canvas: Canvas, radius: Float) {
        card.inset(0.5f, 0.5f)
        canvas.drawRoundRect(card, radius, radius, edge)
        card.inset(-0.5f, -0.5f)
    }

    /** The card's icon, scaling from 0.95 to 1 around its centre while it fades in. */
    private fun drawIcon(canvas: Canvas) {
        val drawable = icon ?: return
        val p = eased(elapsed, ICON_MS)
        if (p <= 0f) return
        drawable.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())
        drawable.alpha = (255 * p).toInt()
        val scale = 0.95f + 0.05f * p
        canvas.save()
        canvas.scale(scale, scale, iconRect.centerX(), iconRect.centerY())
        drawable.draw(canvas)
        canvas.restore()
    }

    /** The basis card's check mark drawn as a stroke: the ring over 0..240 ms, the tick over 160..400 ms. */
    private fun drawCheck(canvas: Canvas, iconSize: Float) {
        toIcon.setRectToRect(UNIT_BOX, iconRect, Matrix.ScaleToFit.FILL)
        ring.reset(); ringUnits.transform(toIcon, ring)
        tick.reset(); tickUnits.transform(toIcon, tick)
        stroke.strokeWidth = 2f * iconSize / 24f
        val ringP = eased(elapsed, RING_MS)
        val tickP = eased(elapsed - TICK_DELAY_MS, TICK_MS)
        if (ringP >= 1f) canvas.drawPath(ring, stroke) else if (ringP > 0f) drawPart(canvas, ring, ringMeasure, ringP)
        if (tickP >= 1f) canvas.drawPath(tick, stroke) else if (tickP > 0f) drawPart(canvas, tick, tickMeasure, tickP)
    }

    private fun drawPart(canvas: Canvas, path: Path, measure: PathMeasure, fraction: Float) {
        measure.setPath(path, false)
        part.reset()
        measure.getSegment(0f, measure.length * fraction, part, true)
        canvas.drawPath(part, stroke)
    }

    /**
     * Wraps the sentence without splitting a Korean word: greedy breaking plus, on API 33 and later, the
     * phrase-based word style so breaks fall only between 어절; balanced so two lines share the width.
     */
    private fun buildLine(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, linePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setLineBreakConfig(
                    LineBreakConfig.Builder().setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build())
            }
            .setIncludePad(false)
            .setLineSpacing(0f, 1.12f)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .build()

    /** Ease-out cubic progress of a [length] ms step [at] ms in, 0 before it starts and 1 after it ends. */
    private fun eased(at: Float, length: Long): Float = EASE_OUT_CUBIC.getInterpolation((at / length).coerceIn(0f, 1f))

    private fun dp(value: Float) = value * density

    private companion object {
        const val UNAVAILABLE = "소재를 불러올 수 없어요"
        const val FACE_FROM = 0xFF0E0F11.toInt()
        const val FACE_TO = 0xFF16181D.toInt()
        const val EDGE = 0x14FFFFFF
        const val HALO = 0x12FFFFFF
        const val ICON_MS = 240L
        const val LINE_DELAY_MS = 40f
        const val LINE_MS = 280L
        const val RING_MS = 240L
        const val TICK_DELAY_MS = 160f
        const val TICK_MS = 240L
        /** The longest step: the check's tick ends at 400 ms. */
        const val ENTRANCE_MS = 400L
        val UNIT_BOX = RectF(0f, 0f, 24f, 24f)
    }
}
