package ai.cashdrive.app.home

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CaptionsTest {
    private val psa = BriefingCatalog.psa

    @Test fun captionAtPicksLatestStart() {
        assertSame(psa.captions[0], psa.captionAt(0))
        assertSame(psa.captions[0], psa.captionAt(2_999))
        assertSame(psa.captions[1], psa.captionAt(3_000))
        assertSame(psa.captions[3], psa.captionAt(11_999))
        assertSame(psa.captions[4], psa.captionAt(12_000))
        assertSame(psa.captions.last(), psa.captionAt(15_000))
        assertSame(psa.captions.last(), psa.captionAt(99_000))
    }

    @Test fun beforeTheFirstCueThereIsNoCaption() {
        assertNull(psa.captionAt(-1))
        val late = VideoCreative("x", "x", "creatives/x.mp4", listOf(Caption(1_000, "a"), Caption(2_000, "b")), "src")
        assertNull(late.captionAt(0))
        assertNull(late.captionAt(999))
        assertEquals("a", late.captionAt(1_000)?.text)
        assertEquals("b", late.captionAt(2_500)?.text)
    }

    @Test fun captionAtDoesNotDependOnListOrder() {
        val shuffled = VideoCreative("x", "x", "creatives/x.mp4", listOf(Caption(6_000, "c"), Caption(0, "a"), Caption(3_000, "b")), "src")
        assertEquals("a", shuffled.captionAt(2_999)?.text)
        assertEquals("b", shuffled.captionAt(3_000)?.text)
        assertEquals("c", shuffled.captionAt(7_000)?.text)
    }

    @Test fun aSelfMadeVideoShowsEachCardSentenceForItsFourSeconds() {
        val briefing = BriefingCatalog.publicService.first { it.id == "school-zone" }
        val video = BriefingCatalog.rotationFor(false).first { it.id == "school-zone" } as VideoCreative
        val lines = briefing.cards.map { it.line }
        assertEquals(lines[0], video.captionAt(0)?.text)
        assertEquals(lines[0], video.captionAt(3_999)?.text)
        assertEquals(lines[1], video.captionAt(4_000)?.text)
        assertEquals(lines[2], video.captionAt(8_000)?.text)
        assertEquals(lines[3], video.captionAt(12_000)?.text)
        assertEquals(lines[3], video.captionAt(15_000)?.text)
    }

    @Test fun emptyCaptionsYieldNothing() {
        assertNull(VideoCreative("x", "x", "creatives/x.mp4", emptyList(), "src").captionAt(5_000))
    }
}
