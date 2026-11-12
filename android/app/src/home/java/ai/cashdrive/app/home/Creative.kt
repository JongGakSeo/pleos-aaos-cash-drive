package ai.cashdrive.app.home

/**
 * One creative played for one ticket. The catalog rotates creatives and the player renders them: a
 * [VideoCreative] is a silent mp4 in the app's assets with caption cues the app draws over it (every
 * creative of the rotation is one), a [MotionCreative] is a briefing drawn as animated cards, which is
 * also what a self-made video falls back to when its clip cannot be shown. Data only, no Android
 * dependency, so it runs in plain JVM unit tests. The session still owns safety and rewards.
 */
sealed class Creative {
    abstract val id: String
    abstract val title: String

    /** The one-line credit shown at the bottom of the player. */
    abstract val source: String
}

/** A self-made briefing; its basis line is the source credit. */
data class MotionCreative(val briefing: Briefing) : Creative() {
    override val id: String get() = briefing.id
    override val title: String get() = briefing.title
    override val source: String get() = briefing.basis
}

/**
 * A silent video bundled in the app's assets. [asset] is the path under assets/ (opened with
 * `AssetManager.openFd`, so the file must stay uncompressed; .mp4 is on AAPT's no-compress list).
 * [captions] are cue points on the ticket timeline; nothing is burned into the file by this app.
 * [width] x [height] is the clip's pixel size, so the stage lays the picture and its caption pill out at
 * the right aspect before the player reports it. [standIn] is the briefing whose cards take the stage
 * if the clip cannot be shown; a video without one (the PSA) gives way to the "unavailable" card.
 */
data class VideoCreative(
    override val id: String,
    override val title: String,
    val asset: String,
    val captions: List<Caption>,
    override val source: String,
    val width: Int = 720,
    val height: Int = 1280,
    val standIn: Briefing? = null,
) : Creative()

/** What the theater shows in place of this video when it cannot be played: its stand-in's cards, or null. */
val VideoCreative.fallback: MotionCreative? get() = standIn?.let(::MotionCreative)

/** One caption cue: [text] is on screen from [startMs] until the next cue starts. */
data class Caption(val startMs: Long, val text: String)

/**
 * The caption on screen at [positionMs]: the cue with the latest start at or before the position, or
 * null before the first cue. The list need not be sorted.
 */
fun VideoCreative.captionAt(positionMs: Long): Caption? =
    captions.filter { it.startMs <= positionMs }.maxByOrNull { it.startMs }
