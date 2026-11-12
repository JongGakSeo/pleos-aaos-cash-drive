package ai.cashdrive.app.home

import ai.cashdrive.app.R
import org.junit.Test
import kotlin.test.assertEquals

class TheaterMathTest {
    private val schoolZone = BriefingCatalog.publicService.first { it.id == "school-zone" }

    @Test fun videoSegmentsFollowTheCaptionCues() {
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 12_000L, 15_000L), TheaterMath.segmentEnds(BriefingCatalog.psa, 15_000))
    }

    @Test fun motionSegmentsFollowTheCards() {
        // 4 s + 4 s + 4 s + 3 s.
        assertEquals(listOf(4_000L, 8_000L, 12_000L, 15_000L), TheaterMath.segmentEnds(MotionCreative(schoolZone), 15_000))
    }

    @Test fun motionSegmentsScaleToTheTicket() {
        assertEquals(listOf(8_000L, 16_000L, 24_000L, 30_000L), TheaterMath.segmentEnds(MotionCreative(schoolZone), 30_000))
    }

    @Test fun aSelfMadeVideoIsCutLikeItsCardsAndSoIsItsStandIn() {
        BriefingCatalog.rotationFor(false).forEach { creative ->
            val video = creative as VideoCreative
            val cards = MotionCreative(video.standIn!!)
            assertEquals(listOf(4_000L, 8_000L, 12_000L, 15_000L), TheaterMath.segmentEnds(video, 15_000), video.id)
            assertEquals(TheaterMath.segmentEnds(cards, 15_000), TheaterMath.segmentEnds(video, 15_000), video.id)
            assertEquals(TheaterMath.segmentEnds(cards, 15_000), TheaterMath.segmentEnds(video.fallback, 15_000), video.id)
        }
        // The PSA has no cards of its own: if it cannot be shown, the bar is one segment.
        assertEquals(listOf(15_000L), TheaterMath.segmentEnds(BriefingCatalog.psa.fallback, 15_000))
    }

    @Test fun anUnavailableCreativeIsOneSegment() {
        assertEquals(listOf(15_000L), TheaterMath.segmentEnds(null, 15_000))
    }

    @Test fun cuesOutsideTheTicketAreIgnoredAndTheLastSegmentEndsTheTicket() {
        val video = VideoCreative("x", "x", "creatives/x.mp4",
            listOf(Caption(20_000, "late"), Caption(5_000, "b"), Caption(0, "a"), Caption(-100, "early")), "src")
        assertEquals(listOf(5_000L, 15_000L), TheaterMath.segmentEnds(video, 15_000))
        assertEquals(listOf(15_000L), TheaterMath.segmentEnds(VideoCreative("x", "x", "a.mp4", emptyList(), "src"), 15_000))
    }

    @Test fun aDegenerateTicketStillHasOneSegment() {
        assertEquals(listOf(1L), TheaterMath.segmentEnds(BriefingCatalog.psa, 0))
    }

    @Test fun segmentAtPicksTheRunningSegment() {
        val ends = listOf(3_000L, 6_000L, 9_000L, 12_000L, 15_000L)
        assertEquals(0, TheaterMath.segmentAt(ends, -1))
        assertEquals(0, TheaterMath.segmentAt(ends, 0))
        assertEquals(0, TheaterMath.segmentAt(ends, 2_999))
        assertEquals(1, TheaterMath.segmentAt(ends, 3_000))
        assertEquals(4, TheaterMath.segmentAt(ends, 14_999))
        assertEquals(4, TheaterMath.segmentAt(ends, 15_000))
        assertEquals(4, TheaterMath.segmentAt(ends, 99_000))
    }

    @Test fun theMotionCardFollowsTheSegments() {
        val ends = TheaterMath.segmentEnds(MotionCreative(schoolZone), 15_000)
        assertEquals(schoolZone.cards[0], schoolZone.cards[TheaterMath.segmentAt(ends, 3_999)])
        assertEquals(schoolZone.cards[1], schoolZone.cards[TheaterMath.segmentAt(ends, 4_000)])
        assertEquals(R.drawable.ic_brief_check, schoolZone.cards[TheaterMath.segmentAt(ends, 14_000)].icon)
    }

    @Test fun remainingRoundsUpToWholeSeconds() {
        assertEquals("0:15 남음", TheaterMath.remaining(0, 15_000))
        assertEquals("0:08 남음", TheaterMath.remaining(7_300, 15_000))
        assertEquals("0:01 남음", TheaterMath.remaining(14_999, 15_000))
        assertEquals("0:00 남음", TheaterMath.remaining(15_000, 15_000))
        assertEquals("0:00 남음", TheaterMath.remaining(20_000, 15_000))
        assertEquals("1:05 남음", TheaterMath.remaining(0, 65_000))
    }

    @Test fun fitKeepsTheAspectInsideTheStage() {
        // The PSA (720x1280) stands by height on the wide stage; a landscape clip lies by width.
        assertEquals(528 to 938, TheaterMath.fit(720, 1280, 961, 938))
        assertEquals(961 to 541, TheaterMath.fit(1920, 1080, 961, 938))
        assertEquals(400 to 300, TheaterMath.fit(4, 3, 400, 300))
        // The self-made clips are square: they fill the height of the wide stage and of the narrow one.
        assertEquals(938 to 938, TheaterMath.fit(1080, 1080, 961, 938))
        assertEquals(702 to 702, TheaterMath.fit(1080, 1080, 840, 702))
        assertEquals(0 to 0, TheaterMath.fit(0, 1280, 961, 938))
        assertEquals(0 to 0, TheaterMath.fit(720, 1280, 0, 938))
    }

    @Test fun headerCountsTheBriefingsOfThisStop() {
        assertEquals("1/1", TheaterMath.count(1, 1))
        assertEquals("2/3", TheaterMath.count(2, 3))
        // Never "3/2" or "0/0", whatever the counters say.
        assertEquals("3/3", TheaterMath.count(3, 2))
        assertEquals("1/1", TheaterMath.count(0, 0))
    }
}
