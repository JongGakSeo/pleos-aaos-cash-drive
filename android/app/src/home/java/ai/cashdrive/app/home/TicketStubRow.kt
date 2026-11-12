package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Today's ticket stubs, as many as the daily cap (design §5, §13-5): each a ticket shape with a
 * half-circle notch in both short sides. A stub not issued yet is a dashed outline; a stub ready to watch
 * is filled amber with a darker perforation; a watched stub is an amber outline with a punched hole.
 * - One row ([perRow] 0, the narrow layout): 44x30dp stubs 8dp apart, shrinking evenly to fit, and
 *   tickets beyond today's issue (carry-over, the welcome ticket) as an amber "+N" pill after the row.
 * - A grid ([perRow] > 0, the wide meter): rows of [perRow] stubs 12dp apart both ways, each as wide as
 *   the row allows up to 104dp and 1.6 times as wide as tall; the view measures its own height from its
 *   width and the panel shows the "+N" beside the section's label instead.
 *
 * New stubs pop in; the panel can hold the newest one back while its flying stub lands, then settle
 * it (-6 degrees to 0). Transform and alpha only.
 */
@SuppressLint("ViewConstructor")
class TicketStubRow(context: Context) : View(context) {
    /** Animation length multiplier from the panel's motion level: 1 parked, 0.5 driving, 0 snaps. */
    var durationScale = 1f

    /** Stubs per row: 0 lays every stub in one row with the "+N" pill after it; more is the wide grid. */
    var perRow = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout(); invalidate()
        }

    private var model = StubLayout(0, 0, MeterMath.SEGMENTS, 0)
    private var heldNewest = false
    private var settle = 1f
    private var pop = 1f
    private var popFrom = Int.MAX_VALUE
    private var blink = 1f
    private var settleAnimator: ValueAnimator? = null
    private var popAnimator: ValueAnimator? = null
    private var blinkAnimator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private val painter = StubPainter(density)
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MeterFace.AMBER }
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = MeterFace.AMBER
        fontFeatureSettings = "tnum"; textAlign = Paint.Align.CENTER
    }
    private val badgeRect = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private var cellTop = 0f
    private var gapPx = 0f
    private var rowGapPx = 0f
    private var across = 0
    private var badgeGapPx = 0f

    /** Width and height of one stub after layout, for the flying stub's size. */
    val cellWidth: Float get() { geometry(); return cellW }
    val cellHeight: Float get() { geometry(); return cellH }

    init {
        describe()
    }

    /** Shows [layout]. Newly ready stubs pop in when [animate]; without it everything snaps and running animations end. */
    fun set(layout: StubLayout, animate: Boolean) {
        if (layout == model) {
            if (!animate) snapAll()
            return
        }
        val previousReady = model.punched + model.ready
        val nextReady = layout.punched + layout.ready
        val resized = layout.punched + layout.ready + layout.dotted != cap()
        popAnimator?.cancel()
        model = layout
        if (resized && perRow > 0) requestLayout()
        describe()
        val duration = (POP_MS * durationScale).toLong()
        if (animate && duration > 0L && nextReady > previousReady && isAttachedToWindow) {
            popFrom = previousReady
            pop = 0f
            popAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = EASE_OUT_CUBIC
                addUpdateListener { pop = it.animatedValue as Float; invalidate() }
                whenDone { popAnimator = null; pop = 1f; popFrom = Int.MAX_VALUE; invalidate() }
                start()
            }
        } else if (!animate) {
            snapAll()
        }
        invalidate()
    }

    /** Hides the newest ready stub (true) until the flying stub lands, then shows it again (false). */
    fun holdNewest(hold: Boolean) {
        if (hold) {
            popAnimator?.cancel()
            settleAnimator?.cancel()
            settle = 1f
        }
        heldNewest = hold
        invalidate()
    }

    /** The newest ready stub lands: a short turn from -6 degrees and a 1.08 scale back to rest. */
    fun settleNewest() {
        settleAnimator?.cancel()
        val duration = (SETTLE_MS * durationScale).toLong()
        if (duration <= 0L || !isAttachedToWindow) { settle = 1f; invalidate(); return }
        settle = 0f
        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { settle = it.animatedValue as Float; invalidate() }
            whenDone { settleAnimator = null; settle = 1f; invalidate() }
            start()
        }
    }

    /** One blink of the ready stubs (alpha dips to 35% and back), for the arrival sequence. */
    fun blink() {
        blinkAnimator?.cancel()
        val duration = (BLINK_MS * durationScale).toLong()
        if (duration <= 0L || !isAttachedToWindow) return
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { blink = 1f - 0.65f * sin(PI * (it.animatedValue as Float)).toFloat(); invalidate() }
            whenDone { blinkAnimator = null; blink = 1f; invalidate() }
            start()
        }
    }

    /** Centre of stub [index] in this view's coordinates (where the flying stub lands). */
    fun slotCenter(index: Int, out: FloatArray) {
        geometry()
        val (col, row) = StubRow.slot(index, across)
        out[0] = paddingLeft + col * (cellW + gapPx) + cellW / 2f
        out[1] = cellTop + row * (cellH + rowGapPx) + cellH / 2f
    }

    /** In the grid the height follows the width (rows of stubs as wide as the row allows); one row keeps its given height. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (perRow <= 0) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val grid = StubRow.grid((width - paddingLeft - paddingRight).toFloat(), cap(), perRow,
            GRID_MAX_W * density, GRID_GAP * density, GRID_ASPECT)
        // 1dp above and below for the outline strokes.
        val height = (grid.height + 2 * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    private fun snapAll() {
        popAnimator?.cancel(); settleAnimator?.cancel(); blinkAnimator?.cancel()
        pop = 1f; popFrom = Int.MAX_VALUE; settle = 1f; blink = 1f
        invalidate()
    }

    private fun describe() {
        contentDescription = buildString {
            append("볼 수 있는 시청권 ${model.ready}장")
            if (model.punched > 0) append(", 다 본 시청권 ${model.punched}장")
            if (model.extra > 0) append(", 추가 시청권 ${model.extra}장")
        }
    }

    private fun cap() = model.punched + model.ready + model.dotted

    /**
     * Stub size from the box: 44x30dp, 8dp apart, scaled down evenly when the row and the badge would
     * not fit the width, and to the height less 1dp above and below for the outline strokes.
     */
    private fun geometry() {
        val cap = cap()
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (cap <= 0 || availW <= 0f || availH <= 0f) { cellW = 0f; return }
        if (perRow > 0) {
            val grid = StubRow.grid(availW, cap, perRow, GRID_MAX_W * density, GRID_GAP * density, GRID_ASPECT)
            across = grid.perRow
            cellW = grid.stubWidth; cellH = grid.stubHeight
            gapPx = GRID_GAP * density; rowGapPx = gapPx
            cellTop = paddingTop + density
            painter.size(cellW, cellH)
            return
        }
        across = cap
        rowGapPx = 0f
        val byHeight = ((availH - 2 * density) / (STUB_H * density)).coerceAtMost(1f)
        badgeText.textSize = BADGE_TEXT * density * byHeight
        badgeGapPx = BADGE_GAP * density * byHeight
        val slot = if (model.extra > 0) badgeWidth() + badgeGapPx else 0f
        val byWidth = StubRow.fitScale(availW, cap, STUB_W * density, GAP * density, slot)
        val scale = minOf(byHeight, byWidth).coerceAtLeast(0.1f)
        cellW = STUB_W * density * scale
        cellH = STUB_H * density * scale
        gapPx = GAP * density * scale
        cellTop = paddingTop + (availH - cellH) / 2f
        painter.size(cellW, cellH)
        badgeStroke.strokeWidth = 1.5f * density
    }

    private fun badgeWidth() = badgeText.measureText("+${model.extra}") + 2 * BADGE_PAD * density

    override fun onDraw(canvas: Canvas) {
        geometry()
        val cap = cap()
        if (cap <= 0 || cellW <= 0f) return
        val readyEnd = model.punched + model.ready
        val newest = readyEnd - 1
        for (i in 0 until cap) {
            val (col, row) = StubRow.slot(i, across)
            canvas.save()
            canvas.translate(paddingLeft + col * (cellW + gapPx), cellTop + row * (cellH + rowGapPx))
            when {
                i < model.punched -> painter.drawWatched(canvas)
                i < readyEnd -> if (!(i == newest && heldNewest)) drawReady(canvas, i, newest)
                else -> painter.drawEmpty(canvas)
            }
            canvas.restore()
        }
        if (model.extra > 0 && perRow <= 0) drawBadge(canvas, paddingLeft + cap * (cellW + gapPx) - gapPx + badgeGapPx)
    }

    private fun drawReady(canvas: Canvas, index: Int, newest: Int) {
        var alpha = blink
        var scale = 1f
        var rotation = 0f
        if (index >= popFrom && pop < 1f) { alpha *= pop; scale = 0.9f + 0.1f * pop }
        if (index == newest && settle < 1f) { rotation = -6f * (1f - settle); scale *= 1f + 0.08f * (1f - settle) }
        canvas.rotate(rotation, cellW / 2f, cellH / 2f)
        canvas.scale(scale, scale, cellW / 2f, cellH / 2f)
        painter.drawReady(canvas, (255 * alpha).roundToInt())
    }

    private fun drawBadge(canvas: Canvas, left: Float) {
        val label = "+${model.extra}"
        val inset = badgeStroke.strokeWidth / 2f
        badgeRect.set(left + inset, cellTop + inset, left + badgeWidth() - inset, cellTop + cellH - inset)
        val radius = badgeRect.height() / 2f
        canvas.drawRoundRect(badgeRect, radius, radius, badgeStroke)
        val textY = badgeRect.centerY() - (badgeText.ascent() + badgeText.descent()) / 2f
        canvas.drawText(label, badgeRect.centerX(), textY, badgeText)
    }

    override fun onDetachedFromWindow() {
        popAnimator?.cancel(); settleAnimator?.cancel(); blinkAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** The review's stub: 44x30dp, 8dp apart, the badge 12dp after the row with 11dp side padding and 18dp text. */
        private const val STUB_W = 44f
        private const val STUB_H = 30f
        private const val GAP = 8f
        private const val BADGE_GAP = 12f
        private const val BADGE_PAD = 11f
        private const val BADGE_TEXT = 18f
        private const val POP_MS = 240L
        private const val SETTLE_MS = 240L
        private const val BLINK_MS = 320L
        /** The wide grid: stubs up to 104dp wide, 1.6 times as wide as tall, 12dp apart both ways. */
        private const val GRID_MAX_W = 104f
        private const val GRID_ASPECT = 1.6f
        private const val GRID_GAP = 12f
    }
}

/**
 * Draws one ticket stub at the origin in the three looks of the row, so the row and the flying stub
 * share one shape: rounded corners (14% of the height) and a half-circle notch (18% of the height) in
 * each short side.
 */
internal class StubPainter(private val density: Float) {
    private val ticket = Path()
    private val notches = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = MeterFace.STUB_DASH
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private var w = -1f
    private var h = -1f

    fun size(width: Float, height: Float) {
        if (width == w && height == h) return
        w = width; h = height
        val corner = h * 0.14f
        val notch = h * 0.18f
        ticket.reset()
        ticket.addRoundRect(0f, 0f, w, h, corner, corner, Path.Direction.CW)
        notches.reset()
        notches.addCircle(0f, h / 2f, notch, Path.Direction.CW)
        notches.addCircle(w, h / 2f, notch, Path.Direction.CW)
        ticket.op(notches, Path.Op.DIFFERENCE)
    }

    /** Not issued yet: a dashed outline (design §5), the slot a ticket will fill. */
    fun drawEmpty(canvas: Canvas) {
        canvas.drawPath(ticket, dashed)
    }

    /** Ready to watch: filled amber, a darker perforation of five dots across the stub end. */
    fun drawReady(canvas: Canvas, alpha: Int = 255) {
        fill.color = MeterFace.AMBER; fill.alpha = alpha
        canvas.drawPath(ticket, fill)
        fill.color = MeterFace.AMBER_DEEP; fill.alpha = alpha
        val x = w * 0.7f
        val r = 1.1f * density * (h / (30f * density))
        for (i in 0 until 5) canvas.drawCircle(x, h * (0.2f + 0.15f * i), r, fill)
        fill.alpha = 255
    }

    /** Watched: a 1.5dp amber outline over a faint amber wash, with a punched hole in the middle. */
    fun drawWatched(canvas: Canvas) {
        fill.color = MeterFace.AMBER; fill.alpha = 0x1F
        canvas.drawPath(ticket, fill)
        edge.color = MeterFace.AMBER; edge.strokeWidth = 1.5f * density
        canvas.drawPath(ticket, edge)
        val hole = h * 0.14f
        fill.color = MeterFace.FACE; fill.alpha = 255
        canvas.drawCircle(w / 2f, h / 2f, hole, fill)
        canvas.drawCircle(w / 2f, h / 2f, hole, edge)
    }
}

/** One ready stub drawn at the view's size: the "ticket +1" that flies from the gauge to the row. */
@SuppressLint("ViewConstructor")
internal class StubGlyphView(context: Context) : View(context) {
    private val painter = StubPainter(resources.displayMetrics.density)

    override fun onDraw(canvas: Canvas) {
        painter.size(width.toFloat(), height.toFloat())
        painter.drawReady(canvas)
    }
}
