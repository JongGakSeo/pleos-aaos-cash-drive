package ai.cashdrive.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.R

/**
 * The briefing theater (design §6). While a ticket plays it covers the scene with #0E0F11 (a 0.3 s fade
 * in park) and shows the creative on a stage between two bands:
 * - Top: "브리핑 · 1/3 · title" (24dp; the count is this stop's, from AdPlayer) and on the right a
 *   "다시 보기" chip for a replay and the "무음" chip (briefings carry no sound).
 * - Stage: a video creative as [VideoCreativeView] (the clip fitted inside the stage with #15171B fillers,
 *   the caption pill at its bottom edge), or a briefing as [MotionCardView] cards. A video that cannot be
 *   shown gives way to its stand-in, the self-made briefing's own cards on the same clock, or the
 *   "소재를 불러올 수 없어요" card for a video without cards (the PSA); [videoActive] turns false, so the
 *   host ticks the ticket on the wall clock and it still ends.
 * - Bottom: the progress bar cut into the creative's cards or caption cues, the time left, the source
 *   line and the amber "완주하면 +10" badge.
 * The host closes it with [hide]: at once when the vehicle leaves park (nothing fades while driving), in
 * 0.2 s otherwise. [covering] turns true once the opening fade has made the theater opaque (at once without
 * it) and false the moment it starts to close: the host keeps the scene's engine drawing under the fading
 * theater and only then stops it, so the theater never fades in over the still. Rendering only: AdPlayer
 * owns the position and completion, the session owns safety and rewards.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class BriefingPlayerView internal constructor(context: Context, private val kit: PleosKit) : FrameLayout(context) {
    private val column = LinearLayout(context)
    private val header = TextView(context)
    private val replayChip = chip("다시 보기", null)
    private val muteChip = chip("무음", R.drawable.ic_player_muted)
    private val stage = FrameLayout(context)
    private val card = MotionCardView(context)
    private val video = VideoCreativeView(context)
    private val segments = SegmentsView(context)
    private val time = TextView(context)
    private val source = TextView(context)
    private val reward = TextView(context)
    private var animated = true
    private var showing = false
    private var opaque = false
    private var failed = false
    /** The cards of the video on stage, shown in its place if it fails; null for a video without cards. */
    private var standIn: Briefing? = null
    /** The ticket's position on the 15-second card layout at the last tick. */
    private var cardTimeMs = 0L
    private var ticket: String? = null
    private var creativeId: String? = null
    private var headerKey: String? = null
    private var bottomInset = 0

    /** True while a video creative plays (or loads) here: the host ticks the ticket with [consumeDeltaMs]. */
    val videoActive: Boolean get() = showing && !failed && video.active

    /** True while the theater is shown and fully opaque: nothing under it can be seen. */
    val covering: Boolean get() = showing && opaque

    /** Called when [covering] turns true (the opening fade ended, or the theater opened without one). */
    var onCovered: (() -> Unit)? = null

    init {
        setBackgroundColor(THEATER)
        // The scene under the theater never takes a touch through it.
        isClickable = true
        column.orientation = LinearLayout.VERTICAL
        addView(column, LayoutParams(-1, -1))

        // Top band: the header line and the chips, one 44dp row.
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        kit.style(header, 24)
        header.setTextColor(Color.WHITE)
        header.maxLines = 1
        header.ellipsize = TextUtils.TruncateAt.END
        header.fontFeatureSettings = "tnum"
        top.addView(header, LinearLayout.LayoutParams(0, -2, 1f))
        replayChip.visibility = View.GONE
        top.addView(replayChip, LinearLayout.LayoutParams(-2, dp(CHIP_HEIGHT)).apply { leftMargin = dp(16) })
        muteChip.contentDescription = "무음, 브리핑에는 소리가 없어요"
        top.addView(muteChip, LinearLayout.LayoutParams(-2, dp(CHIP_HEIGHT)).apply { leftMargin = dp(12) })
        column.addView(top, LinearLayout.LayoutParams(-1, dp(CHIP_HEIGHT)))

        // Stage: one of the card or the video is visible.
        card.visibility = View.GONE
        stage.addView(card, LayoutParams(-1, -1))
        video.visibility = View.GONE
        stage.addView(video, LayoutParams(-1, -1))
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(24) })

        // Bottom band: the progress bar, then time | source ... reward.
        column.addView(segments, LinearLayout.LayoutParams(-1, dp(6)))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        kit.style(time, 24)
        time.setTextColor(Color.WHITE)
        time.fontFeatureSettings = "tnum"
        row.addView(time, LinearLayout.LayoutParams(-2, -2))
        row.addView(View(context).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(dp(1), dp(20)).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        })
        kit.style(source, 22)
        source.setTextColor(SOURCE)
        source.maxLines = 1
        source.ellipsize = TextUtils.TruncateAt.END
        row.addView(source, LinearLayout.LayoutParams(0, -2, 1f))
        kit.style(reward, 22, bold = true)
        reward.setTextColor(MeterFace.AMBER)
        reward.text = "완주하면 +$REWARD_POINTS"
        reward.fontFeatureSettings = "tnum"
        reward.gravity = Gravity.CENTER
        reward.setPadding(dp(16), 0, dp(16), 0)
        reward.background = kit.rounded(REWARD_BACK, BADGE_HEIGHT / 2f)
        reward.contentDescription = "끝까지 보면 $REWARD_POINTS 크레딧이 쌓여요"
        row.addView(reward, LinearLayout.LayoutParams(-2, dp(BADGE_HEIGHT)).apply { leftMargin = dp(16) })
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        applyPadding()
    }

    /**
     * Shows [creative] for the ticket in [progress]; called on every tick. A new ticket or creative enters
     * anew (the video loads from the ticket's saved position); otherwise only the chrome, the caption, the
     * card and the picture's sync follow the position.
     */
    /** True from [show] until [hide]: a briefing is on screen (its fade-out no longer counts). */
    val isShowing: Boolean get() = showing

    fun show(creative: Creative, progress: AdPlayer.Progress, replay: Boolean) {
        val opening = !showing
        if (opening) open()
        if (progress.adId != ticket || creative.id != creativeId) {
            ticket = progress.adId
            creativeId = creative.id
            failed = false
            present(creative, progress, if (opening && animated) CONTENT_DELAY_MS else 0L)
        }
        setHeader(TheaterMath.count(progress.index, progress.total), creative.title)
        replayChip.visibility = if (replay) View.VISIBLE else View.GONE
        val shown = if (failed) (creative as? VideoCreative)?.fallback else creative
        segments.set(TheaterMath.segmentEnds(shown, progress.durationMs), progress.positionMs)
        val left = TheaterMath.remaining(progress.positionMs, progress.durationMs)
        if (time.text.toString() != left) time.text = left
        if (source.text.toString() != creative.source) source.text = creative.source
        cardTimeMs = cardTime(progress)
        if (failed) {
            standIn?.let { card.show(it, cardTimeMs) }
            return
        }
        when (creative) {
            is VideoCreative -> {
                video.syncTo(progress.positionMs, playing = true)
                video.setCaption(creative.captionAt(progress.positionMs)?.text)
            }
            is MotionCreative -> card.show(creative.briefing, cardTime(progress))
        }
    }

    /**
     * Closes the theater. The picture stops at once in every case; [animate] (park, motion allowed) fades the
     * theater out in 0.2 s, otherwise it goes in the same frame.
     */
    fun hide(animate: Boolean) {
        if (!showing) return
        showing = false
        opaque = false
        ticket = null
        creativeId = null
        failed = false
        standIn = null
        video.release()
        log("hide animate=${animate && animated}")
        animate().cancel()
        if (animate && animated && isAttachedToWindow) {
            animate().alpha(0f).setDuration(CLOSE_MS).setInterpolator(EASE_OUT_CUBIC).withLayer()
                .withEndAction { if (!showing) closed() }.start()
        } else {
            closed()
        }
    }

    /** What the picture played since the last tick (0 unless [videoActive]). */
    fun consumeDeltaMs(): Long = if (videoActive) video.consumeDeltaMs() else 0L

    /** Transitions on (park with motion) or off (reduce motion): off lands every running fade at its end. */
    fun setAnimated(value: Boolean) {
        if (value == animated) return
        animated = value
        card.animated = value
        video.animated = value
        segments.animated = value
        if (!value) {
            animate().cancel()
            if (showing) { alpha = 1f; cover() } else if (visibility == View.VISIBLE) closed()
        }
    }

    /** Extra space under the bottom band (narrow layout: the toast's own strip). */
    fun setBottomInset(px: Int) {
        if (px == bottomInset) return
        bottomInset = px
        applyPadding()
    }

    /** The theater is the same dark room in both looks; the tokens are accepted for the host's uniform theming call. */
    fun setTokens(@Suppress("UNUSED_PARAMETER") tokens: PleosTokens) = Unit

    private fun open() {
        showing = true
        opaque = false
        animate().cancel()
        val wasVisible = visibility == View.VISIBLE
        visibility = View.VISIBLE
        if (animated && isAttachedToWindow) {
            if (!wasVisible) alpha = 0f
            animate().alpha(1f).setDuration(OPEN_MS).setInterpolator(EASE_OUT_CUBIC).withEndAction { cover() }.start()
        } else {
            alpha = 1f
            cover()
        }
    }

    /** The theater is opaque now: the host may stop the scene under it. Once per opening. */
    private fun cover() {
        if (!showing || opaque) return
        opaque = true
        onCovered?.invoke()
    }

    private fun closed() {
        visibility = View.GONE
        alpha = 1f
        card.reset()
        video.setCaption(null)
    }

    private fun present(creative: Creative, progress: AdPlayer.Progress, delayMs: Long) {
        log("present ${creative.id} ticket=${progress.adId} at ${progress.positionMs}ms")
        card.reset()
        when (creative) {
            is VideoCreative -> {
                standIn = creative.standIn
                card.visibility = View.GONE
                video.visibility = View.VISIBLE
                video.contentDescription = creative.title
                video.setCaption(null)
                video.load(creative.asset, creative.width, creative.height, progress.positionMs, progress.durationMs) { onVideoFailed() }
            }
            is MotionCreative -> {
                standIn = null
                video.release()
                video.visibility = View.GONE
                card.visibility = View.VISIBLE
                card.show(creative.briefing, cardTime(progress), delayMs)
            }
        }
    }

    /**
     * The video could not be shown: its stand-in takes the stage (the briefing's own cards at the ticket's
     * position, or the "unavailable" card for a video without cards) and the ticket goes on the wall clock.
     */
    private fun onVideoFailed() {
        if (failed || !showing) return
        failed = true
        val cards = standIn
        log("video failed: ${if (cards != null) "briefing cards" else "unavailable card"}, wall clock")
        video.release()
        video.visibility = View.GONE
        card.visibility = View.VISIBLE
        if (cards != null) card.show(cards, cardTimeMs) else card.showUnavailable()
    }

    private fun setHeader(count: String, title: String) {
        val key = "$count|$title"
        if (key == headerKey) return
        headerKey = key
        header.text = SpannableStringBuilder().apply {
            append("브리핑", StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" · $count · ", ForegroundColorSpan(DIM), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(title)
        }
        header.contentDescription = "브리핑 $count, $title"
    }

    /** The ticket's position on the briefing's 15-second card layout. */
    private fun cardTime(progress: AdPlayer.Progress): Long =
        progress.positionMs * BriefingCatalog.DURATION_MS / progress.durationMs.coerceAtLeast(1)

    private fun applyPadding() {
        column.setPadding(dp(24), dp(24), dp(24), dp(24) + bottomInset)
    }

    /** A 44dp pill: optional 20dp glyph 8dp before the 22dp text, white on white 12%. */
    private fun chip(label: String, icon: Int?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // The glyph's own margin makes 12dp read like the 16dp on the text side.
        setPadding(dp(if (icon != null) 12 else 16), 0, dp(16), 0)
        background = kit.rounded(CHIP, CHIP_HEIGHT / 2f)
        if (icon != null) {
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(CHIP_GLYPH)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        }
        addView(kit.text(label, 22, Color.WHITE))
    }

    private fun dp(value: Int) = kit.dp(value)

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    /**
     * The progress bar: one rounded segment per card or caption cue, widths in proportion to their time,
     * 8dp apart; played white over a white 22% track. Between ticks the fill glides to the new position
     * (120 ms, linear) so it moves like a player's bar instead of stepping ten times a second; a jump back
     * or of more than a second (a resume) snaps, and without [animated] every update snaps.
     */
    private class SegmentsView(context: Context) : View(context) {
        var animated = true
        private var ends: List<Long> = listOf(1L)
        private var from = 0L
        private var to = 0L
        private var setAt = 0L
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val rect = RectF()
        private val gap = 8f * resources.displayMetrics.density

        fun set(ends: List<Long>, positionMs: Long) {
            val now = SystemClock.uptimeMillis()
            val shown = shownAt(now)
            if (ends != this.ends || !animated || positionMs < shown || positionMs - shown > JUMP_MS) {
                this.ends = ends
                from = positionMs
            } else {
                from = shown
            }
            to = positionMs
            setAt = now
            invalidate()
        }

        private fun shownAt(now: Long): Long {
            if (to <= from) return to
            val t = ((now - setAt).toFloat() / GLIDE_MS).coerceIn(0f, 1f)
            return from + ((to - from) * t).toLong()
        }

        override fun onDraw(canvas: Canvas) {
            val position = shownAt(SystemClock.uptimeMillis())
            val total = ends.last().coerceAtLeast(1)
            val usable = width - gap * (ends.size - 1)
            if (usable <= 0f) return
            val radius = height / 2f
            var x = 0f
            var start = 0L
            for (end in ends) {
                val w = usable * (end - start) / total
                rect.set(x, 0f, x + w, height.toFloat())
                canvas.drawRoundRect(rect, radius, radius, track)
                val part = ((position - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
                if (part > 0f) {
                    rect.right = x + w * part
                    canvas.drawRoundRect(rect, radius, radius, fill)
                }
                x += w + gap
                start = end
            }
            if (position < to) postInvalidateOnAnimation()
        }

        private companion object {
            const val TRACK = 0x38FFFFFF
            const val GLIDE_MS = 120f
            const val JUMP_MS = 1_000L
        }
    }

    private companion object {
        const val TAG = "CashDriveTheater"
        const val THEATER = 0xFF0E0F11.toInt()
        const val CHIP = 0x1FFFFFFF
        const val CHIP_GLYPH = 0xE0FFFFFF.toInt()
        const val CHIP_HEIGHT = 44
        const val BADGE_HEIGHT = 40
        /** Secondary text on the theater: the header's count and dots (white 56%, 6.4:1 on #0E0F11). */
        const val DIM = 0x8FFFFFFF.toInt()
        /** The source line (white 64%, 8:1). */
        const val SOURCE = 0xA3FFFFFF.toInt()
        const val DIVIDER = 0x3DFFFFFF
        /** The amber badge's wash (amber 14%); the amber text on it is 7.2:1. */
        const val REWARD_BACK = 0x24E0A93C
        /** Points a completed ticket credits (the session's MockCredit). */
        const val REWARD_POINTS = 10
        const val OPEN_MS = 300L
        const val CLOSE_MS = 200L
        /** The first card enters while the theater fades in, a beat behind it. */
        const val CONTENT_DELAY_MS = 120L
    }
}
