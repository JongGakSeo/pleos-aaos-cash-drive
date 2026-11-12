package ai.cashdrive.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ai.cashdrive.app.BuildConfig
import java.util.Locale
import kotlin.math.abs

/**
 * A silent video creative on the theater's stage (design §6, §13-2): a TextureView fed by a MediaPlayer
 * (muted, no loop) playing an mp4 from the app's assets, fitted inside the stage with the stage's #15171B
 * fillers beside it (a portrait clip stands by height, a landscape one lies by width), and the caption
 * pill at the picture's bottom edge (white 28dp on black 55%, the app's own cues over the clean file).
 *
 * AdPlayer stays the owner of the position and of completion: the host ticks it with [consumeDeltaMs],
 * what the picture played since the last tick ([VideoClock]), and hands the resulting position back
 * through [syncTo]. [load] starts at the ticket's saved position, so a briefing stopped by leaving park
 * resumes where it stopped. A missing or broken file, a player error or a picture that does not move
 * for [VideoClock.STALL_MS] ends in [load]'s onError exactly once with the player released; the host
 * then shows the stand-in card and ticks on the wall clock, so the ticket still ends.
 */
@SuppressLint("ViewConstructor")
class VideoCreativeView(context: Context) : ViewGroup(context), TextureView.SurfaceTextureListener {
    /** False (reduce motion): the first frame and caption changes appear at once. */
    var animated = true

    /** True while a video is loaded and has not failed: the host ticks the ticket with [consumeDeltaMs]. */
    val active: Boolean get() = clock?.active == true

    private val density = resources.displayMetrics.density
    private val texture = TextureView(context)
    /** The stage colour over the picture until its first frame, so neither black nor an old frame flashes. */
    private val cover = View(context)
    private val caption = TextView(context)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var clock: VideoClock? = null
    private var onError: (() -> Unit)? = null
    private var generation = 0
    private var prepared = false
    private var seeking = false
    private var completed = false
    private var playing = false
    /**
     * start() was called and not undone by pause(). Kept here because MediaPlayer.isPlaying turns false at
     * the end of the file before onCompletion arrives, and a start() in that gap would replay the clip from 0.
     */
    private var started = false
    private var awaitingFrame = false
    private var videoWidth = PORTRAIT_W
    private var videoHeight = PORTRAIT_H
    private var captionText: String? = null

    init {
        background = GradientDrawable().apply { setColor(FILLER); cornerRadius = dp(20f) }
        clipToOutline = true
        texture.surfaceTextureListener = this
        texture.isOpaque = false
        addView(texture)
        cover.setBackgroundColor(FILLER)
        addView(cover)
        caption.setTextColor(Color.WHITE)
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28f)
        caption.typeface = Typeface.DEFAULT
        caption.includeFontPadding = false
        caption.setLineSpacing(dp(6f), 1f)
        caption.gravity = Gravity.CENTER
        caption.maxLines = 3
        caption.ellipsize = TextUtils.TruncateAt.END
        caption.breakStrategy = Layout.BREAK_STRATEGY_BALANCED
        caption.textLocale = Locale.KOREA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) caption.lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        caption.background = GradientDrawable().apply { setColor(CAPTION_BACK); cornerRadius = dp(16f) }
        caption.setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(12f).toInt())
        caption.visibility = GONE
        addView(caption)
    }

    /**
     * Opens [asset] (a path under assets/) and plays it from [startMs] once prepared, muted. [width] x
     * [height] is the clip's size as bundled: the picture and its caption pill take that aspect at once, so
     * nothing resizes when the player reports it (a different reported size still wins). [durationMs] is
     * the ticket's length. Replaces whatever was loaded. [onError] runs at most once, never from inside
     * this call, and only for this load.
     */
    fun load(asset: String, width: Int, height: Int, startMs: Long, durationMs: Long, onError: () -> Unit) {
        release()
        setVideoSize(width, height)
        val token = generation
        this.onError = onError
        clock = VideoClock(durationMs).also { it.restart(startMs, now()) }
        cover.animate().cancel()
        cover.alpha = 1f
        cover.visibility = VISIBLE
        awaitingFrame = true
        val mp = MediaPlayer()
        player = mp
        try {
            context.assets.openFd(asset).use { mp.setDataSource(it) }
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnPreparedListener { if (token == generation) onPrepared(it) }
            mp.setOnSeekCompleteListener { if (token == generation) onSeekComplete() }
            mp.setOnCompletionListener { if (token == generation) { completed = true; log("complete") } }
            mp.setOnVideoSizeChangedListener { _, w, h -> if (token == generation) setVideoSize(w, h) }
            mp.setOnErrorListener { _, what, extra -> if (token == generation) fail("player error $what/$extra"); true }
            surface?.let(mp::setSurface)
            mp.prepareAsync()
            log("load $asset from ${startMs}ms")
        } catch (e: Exception) {
            // Report on the next turn of the main loop: the host is still inside its show() call.
            main.post { if (token == generation) fail("open ${e.javaClass.simpleName}") }
        }
    }

    /**
     * The ticket is at [positionMs] and should be [playing]. Starts or pauses the picture and seeks when it
     * is more than [RESYNC_MS] away from the ticket (it never is while the ticket follows [consumeDeltaMs]).
     */
    fun syncTo(positionMs: Long, playing: Boolean) {
        this.playing = playing
        val mp = player ?: return
        val clock = clock ?: return
        if (!prepared || completed || !clock.active) return
        try {
            if (!seeking && abs(mp.currentPosition - positionMs) > RESYNC_MS) {
                seeking = true
                mp.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
                clock.restart(positionMs, now())
            }
            if (playing && !started) {
                mp.start()
                started = true
                clock.restart(clock.positionMs, now())
            } else if (!playing && started) {
                mp.pause()
                started = false
            }
        } catch (e: IllegalStateException) {
            fail("sync ${e.message}")
        }
    }

    /**
     * What the picture played since the last call, for AdPlayer.tick: 0 while it loads or seeks, the rest
     * of the ticket once the file has ended. Gives the video up (onError) when it has not moved for
     * [VideoClock.STALL_MS] while it should be playing.
     */
    fun consumeDeltaMs(): Long {
        val clock = clock ?: return 0
        if (!clock.active) return 0
        if (completed) return clock.complete()
        val now = now()
        val mp = player
        var step = 0L
        if (mp != null && prepared && !seeking) {
            step = try {
                clock.advance(mp.currentPosition.toLong(), now)
            } catch (e: IllegalStateException) {
                fail("position ${e.message}")
                return 0
            }
        }
        if (playing && clock.stalled(now)) fail(if (prepared) "stalled" else "prepare timeout")
        return step
    }

    /** The caption cue on screen; null hides the pill. A change fades out and in (100 + 160 ms) in park. */
    fun setCaption(text: String?) {
        if (text == captionText) return
        val shown = captionText != null && caption.visibility == VISIBLE
        captionText = text
        caption.animate().cancel()
        if (text == null) {
            caption.visibility = GONE
            return
        }
        caption.contentDescription = text
        if (!animated || !shown) {
            caption.text = text
            caption.alpha = 1f
            caption.visibility = VISIBLE
            return
        }
        caption.animate().alpha(0f).setDuration(CAPTION_OUT_MS).setInterpolator(EASE_OUT_CUBIC).withEndAction {
            caption.text = captionText
            caption.animate().alpha(1f).setDuration(CAPTION_IN_MS).setInterpolator(EASE_OUT_CUBIC).start()
        }.start()
    }

    /** Stops and frees the player at once. The last frame stays on the texture until the next [load] covers it. */
    fun release() {
        generation += 1
        main.removeCallbacksAndMessages(null)
        val mp = player
        player = null
        prepared = false
        seeking = false
        completed = false
        playing = false
        started = false
        clock = null
        onError = null
        if (mp != null) {
            try { mp.release() } catch (_: Exception) { }
            log("release")
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        prepared = true
        setVideoSize(mp.videoWidth, mp.videoHeight)
        val clock = clock ?: return
        val at = clock.positionMs
        try {
            if (at > 0) {
                seeking = true
                mp.seekTo(at, MediaPlayer.SEEK_CLOSEST)
            }
            clock.restart(at, now())
            if (playing) {
                mp.start()
                started = true
            }
            log("prepared ${mp.duration}ms, start at ${at}ms")
        } catch (e: IllegalStateException) {
            fail("start ${e.message}")
        }
    }

    private fun onSeekComplete() {
        seeking = false
        // A paused picture gets no further frames: show the one the seek landed on.
        if (awaitingFrame && !playing) reveal()
    }

    private fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == videoWidth && h == videoHeight)) return
        videoWidth = w
        videoHeight = h
        requestLayout()
    }

    /** Fails the video once: the clock goes inactive, the player is freed and the host hears onError. */
    private fun fail(reason: String) {
        val clock = clock ?: return
        if (!clock.active) return
        clock.fail()
        log("fail: $reason")
        val callback = onError
        val mp = player
        player = null
        prepared = false
        started = false
        if (mp != null) try { mp.release() } catch (_: Exception) { }
        callback?.invoke()
    }

    /** The first frame is on the texture: lift the cover (150 ms) so the picture appears without a flash. */
    private fun reveal() {
        awaitingFrame = false
        cover.animate().cancel()
        if (animated) {
            cover.animate().alpha(0f).setDuration(REVEAL_MS).setInterpolator(EASE_OUT_CUBIC)
                .withEndAction { cover.visibility = INVISIBLE }.start()
        } else {
            cover.alpha = 0f
            cover.visibility = INVISIBLE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val (pw, ph) = TheaterMath.fit(videoWidth, videoHeight, w, h)
        val exactW = MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY)
        texture.measure(exactW, exactH)
        cover.measure(exactW, exactH)
        // The pill spans the picture minus 16dp a side, so its width does not jump from cue to cue.
        val captionW = (pw - 2 * dp(CAPTION_INSET)).toInt().coerceAtLeast(0)
        caption.measure(MeasureSpec.makeMeasureSpec(captionW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pw = texture.measuredWidth
        val ph = texture.measuredHeight
        val left = (r - l - pw) / 2
        val top = (b - t - ph) / 2
        texture.layout(left, top, left + pw, top + ph)
        cover.layout(left, top, left + pw, top + ph)
        val cw = caption.measuredWidth
        val ch = caption.measuredHeight
        val captionLeft = left + (pw - cw) / 2
        val captionBottom = top + ph - dp(CAPTION_BOTTOM).toInt()
        caption.layout(captionLeft, captionBottom - ch, captionLeft + cw, captionBottom)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val next = Surface(st)
        surface = next
        try { player?.setSurface(next) } catch (_: IllegalStateException) { }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        try { player?.setSurface(null) } catch (_: IllegalStateException) { }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        if (awaitingFrame && prepared && !seeking) reveal()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun dp(value: Float) = value * density

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "CashDriveVideo"
        /** The stage's filler beside a portrait picture (design §6), also the cover before the first frame. */
        const val FILLER = 0xFF15171B.toInt()
        const val CAPTION_BACK = 0x8C000000.toInt()
        const val CAPTION_INSET = 16f
        const val CAPTION_BOTTOM = 24f
        const val CAPTION_OUT_MS = 100L
        const val CAPTION_IN_MS = 160L
        const val REVEAL_MS = 150L
        const val RESYNC_MS = 400L
        /** Before the first [load] names a size, lay the picture out as the portrait PSA clip. */
        const val PORTRAIT_W = 720
        const val PORTRAIT_H = 1280
    }
}
