package ai.cashdrive.app.home

/**
 * The ticket's clock while a video creative plays (design §6, plan D2). AdPlayer stays the owner of the
 * position and of completion; this class only turns the media player's position into the steps AdPlayer
 * ticks with, so the ticket advances exactly as far as the picture did. Pure Kotlin with no Android
 * types: the view feeds it the media position and the uptime, the JVM tests feed it numbers.
 *
 * - [restart] (load, seek): the picture is about to show [positionMs]; the mark moves there.
 * - [advance]: the media position grew past the mark; the growth is the step. A position behind the
 *   mark (a seek in flight, a jump back) yields 0 and keeps the mark, so no stretch is counted twice.
 * - [complete]: the file ended; the rest of the ticket is handed out once, so a shorter file still ends it.
 * - [stalled]: the picture has not moved for [STALL_MS] since the last growth or restart (a load that
 *   never prepares, a decoder that hangs). The view then gives up the video with [fail].
 * - [fail]: the clock goes inactive for good; the host ticks the ticket on the wall clock from there, so
 *   a ticket never hangs on a broken video (Review Focus 2).
 */
class VideoClock(private val durationMs: Long) {
    /** False once the video failed: the ticket then runs on the wall clock. */
    var active = true; private set

    /** The media position already handed to the ticket, within 0..durationMs. */
    var positionMs = 0L; private set

    private var done = false
    private var movedAtMs = 0L

    /** The picture starts (again) at [positionMs]: after a load or a seek, and for a new ticket. */
    fun restart(positionMs: Long, nowMs: Long) {
        this.positionMs = positionMs.coerceIn(0, durationMs.coerceAtLeast(0))
        done = false
        movedAtMs = nowMs
    }

    /** The step since the last call: how far [mediaPositionMs] grew past the mark, never negative. */
    fun advance(mediaPositionMs: Long, nowMs: Long): Long {
        if (!active || done) return 0
        val step = (mediaPositionMs.coerceAtMost(durationMs) - positionMs).coerceAtLeast(0)
        if (step > 0) {
            positionMs += step
            movedAtMs = nowMs
        }
        return step
    }

    /** The file ended: the rest of the ticket, once. */
    fun complete(): Long {
        if (!active || done) return 0
        done = true
        val rest = (durationMs - positionMs).coerceAtLeast(0)
        positionMs = durationMs.coerceAtLeast(0)
        return rest
    }

    /** True when the picture should be moving but has not for [STALL_MS]. */
    fun stalled(nowMs: Long): Boolean = active && !done && nowMs - movedAtMs >= STALL_MS

    fun fail() {
        active = false
    }

    companion object {
        /** A local 15-second file prepares in well under a second; three seconds still is a broken video. */
        const val STALL_MS = 3_000L
    }
}
