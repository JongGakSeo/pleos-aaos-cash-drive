package ai.cashdrive.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Static, scalable Canvas art for the three selectable vehicle skins. */
class SkinArtView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var skin = "horse"
    private var night = false

    fun setSkin(value: String) {
        skin = value.takeIf { it in setOf("horse", "mine", "gpu") } ?: "horse"
        invalidate()
    }

    fun setNight(value: Boolean) {
        night = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width.toFloat(), height.toFloat())
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val scale = side / 260f
        val ink = if (night) 0xFFE7EDDF.toInt() else 0xFF17352B.toInt()
        val accent = if (night) 0xFFC9DC91.toInt() else 0xFF9CB85F.toInt()
        val paper = if (night) 0xFF303930.toInt() else 0xFFE3E7D8.toInt()
        paint.style = Paint.Style.FILL
        paint.color = paper
        canvas.drawCircle(cx, cy, side * .44f, paint)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        when (skin) {
            "mine" -> drawMine(canvas, ink, accent)
            "gpu" -> drawGpu(canvas, ink, accent, paper)
            else -> drawHorse(canvas, ink, accent)
        }
        canvas.restore()
    }

    private fun drawHorse(canvas: Canvas, ink: Int, accent: Int) {
        paint.color = ink
        canvas.drawOval(RectF(-62f, -6f, 38f, 47f), paint) // barrel
        val neck = Path().apply {
            moveTo(20f, 3f); lineTo(49f, -70f); lineTo(70f, -68f); lineTo(57f, -24f); lineTo(41f, 23f); close()
        }
        canvas.drawPath(neck, paint)
        val head = Path().apply {
            moveTo(52f, -67f); lineTo(67f, -95f); lineTo(98f, -91f); lineTo(111f, -76f)
            lineTo(98f, -61f); lineTo(69f, -57f); close()
        }
        canvas.drawPath(head, paint)
        val ears = Path().apply {
            moveTo(68f, -88f); lineTo(64f, -111f); lineTo(80f, -94f); close()
            moveTo(87f, -90f); lineTo(99f, -109f); lineTo(99f, -84f); close()
        }
        canvas.drawPath(ears, paint)
        val mane = Path().apply {
            moveTo(51f, -65f); lineTo(18f, -52f); lineTo(29f, -21f); lineTo(51f, -42f); close()
        }
        paint.color = accent; canvas.drawPath(mane, paint)
        paint.color = ink; paint.strokeWidth = 9f
        canvas.drawLine(-42f, 35f, -37f, 91f, paint)
        canvas.drawLine(-10f, 37f, -14f, 91f, paint)
        canvas.drawLine(17f, 31f, 12f, 91f, paint)
        canvas.drawLine(34f, 20f, 43f, 91f, paint)
        canvas.drawLine(-56f, 6f, -77f, 30f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(93f, -78f, 2.8f, Paint(3).apply { color = accent })
    }

    private fun drawMine(canvas: Canvas, ink: Int, accent: Int) {
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = 11f
        canvas.drawArc(RectF(-82f, -79f, 82f, 68f), 180f, 180f, false, paint)
        canvas.drawLine(-82f, -5f, -82f, 52f, paint)
        canvas.drawLine(82f, -5f, 82f, 52f, paint)
        paint.style = Paint.Style.FILL
        paint.color = accent
        canvas.drawRoundRect(RectF(-64f, 15f, 64f, 64f), 9f, 9f, paint)
        paint.color = ink
        val ore = Path().apply {
            moveTo(-47f, 15f); lineTo(-30f, -15f); lineTo(-12f, 13f)
            lineTo(9f, -25f); lineTo(44f, 15f); close()
        }
        canvas.drawPath(ore, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
        canvas.drawLine(-91f, 73f, 91f, 73f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(-38f, 72f, 10f, paint); canvas.drawCircle(38f, 72f, 10f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 8f
        canvas.drawLine(-44f, -65f, 50f, -106f, paint)
        canvas.drawLine(4f, -86f, 48f, -54f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawGpu(canvas: Canvas, ink: Int, accent: Int, panel: Int) {
        for (row in 0..2) {
            val y = -82f + row * 59f
            paint.color = ink
            canvas.drawRoundRect(RectF(-75f, y, 75f, y + 46f), 8f, 8f, paint)
            paint.color = panel
            canvas.drawRoundRect(RectF(-59f, y + 12f, 8f, y + 34f), 4f, 4f, paint)
            paint.color = accent
            canvas.drawCircle(47f, y + 23f, 7f, paint)
            paint.color = if (night) 0xFF87917F.toInt() else 0xFF75816D.toInt()
            paint.strokeWidth = 3f
            canvas.drawLine(-49f, y + 23f, -3f, y + 23f, paint)
        }
        paint.color = accent; paint.strokeWidth = 5f
        canvas.drawLine(-31f, 111f, 31f, 111f, paint)
    }
}
