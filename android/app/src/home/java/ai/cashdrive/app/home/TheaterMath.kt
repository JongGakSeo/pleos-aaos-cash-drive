package ai.cashdrive.app.home

import kotlin.math.roundToInt

/**
 * The briefing theater's arithmetic (design §6), kept out of the views so it runs in JVM tests: where the
 * progress bar's segments end, which segment (card or caption) is running, the remaining-time label and
 * how a creative sits on the stage. No Android dependency.
 */
object TheaterMath {
    /**
     * Ends of the progress bar's segments on the ticket timeline (ms). A motion briefing has one segment
     * per card (its cards are laid out on [BriefingCatalog.DURATION_MS] and scale to the ticket), a video
     * one per caption cue inside the ticket; null (the creative could not be shown) is one segment. The
     * last end is always the ticket's end, so the bar fills exactly when the ticket completes.
     */
    fun segmentEnds(creative: Creative?, durationMs: Long): List<Long> {
        val end = durationMs.coerceAtLeast(1)
        val inner: List<Long> = when (creative) {
            is MotionCreative -> {
                var sum = 0.0
                creative.briefing.cards.dropLast(1).map { card ->
                    sum += card.seconds * 1_000.0
                    (sum * end / BriefingCatalog.DURATION_MS).toLong()
                }
            }
            is VideoCreative -> creative.captions.map { it.startMs }.filter { it in 1 until end }.sorted()
            null -> emptyList()
        }
        return (inner.filter { it in 1 until end } + end).distinct()
    }

    /** Index of the segment running at [positionMs]: the first whose end lies past it, else the last. */
    fun segmentAt(ends: List<Long>, positionMs: Long): Int {
        val index = ends.indexOfFirst { positionMs < it }
        return if (index < 0) (ends.size - 1).coerceAtLeast(0) else index
    }

    /** "0:08 남음": whole seconds left, rounded up so the label never promises less time than there is. */
    fun remaining(positionMs: Long, durationMs: Long): String {
        val left = ((durationMs - positionMs).coerceAtLeast(0) + 999) / 1_000
        return "%d:%02d 남음".format(left / 60, left % 60)
    }

    /** The stop's briefing count for the header ("1/3"): index within 1..total, total at least 1. */
    fun count(index: Int, total: Int): String {
        val all = total.coerceAtLeast(1)
        return "${index.coerceIn(1, maxOf(all, index))}/${maxOf(all, index)}"
    }

    /** Size of a [contentW] x [contentH] picture fitted inside [boxW] x [boxH], aspect kept; 0 x 0 when either is empty. */
    fun fit(contentW: Int, contentH: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
        if (contentW <= 0 || contentH <= 0 || boxW <= 0 || boxH <= 0) return 0 to 0
        val scale = minOf(boxW.toFloat() / contentW, boxH.toFloat() / contentH)
        return (contentW * scale).roundToInt().coerceAtMost(boxW) to (contentH * scale).roundToInt().coerceAtMost(boxH)
    }
}
