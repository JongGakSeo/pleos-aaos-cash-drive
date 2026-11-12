package ai.cashdrive.app.home

import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.R
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BriefingCatalogTest {
    private val catalog = BriefingCatalog

    @Test fun everyBriefingLastsFifteenSeconds() {
        catalog.publicService.forEach { assertEquals(15f, it.cards.sumOf { c -> c.seconds.toDouble() }.toFloat(), 0.01f, it.id) }
        assertEquals(3, catalog.publicService.size)
        assertEquals(15_000L, catalog.DURATION_MS)
    }

    @Test fun rotationFollowsThePsaBuildSwitch() {
        // -Pkilomoa.includePsa=true (after the permission record is complete) puts the PSA first; the default
        // build starts with the self-made right-turn briefing.
        assertEquals(BuildConfig.INCLUDE_PSA, catalog.INCLUDE_PSA)
        val first = catalog.rotation.first()
        assertIs<VideoCreative>(first)
        if (BuildConfig.INCLUDE_PSA) assertSame(catalog.psa, first) else assertEquals("right-turn-stop", first.id)
        assertEquals(listOf("psa-right-turn", "school-zone", "emergency-yield"), catalog.rotationFor(true).map { it.id })
        assertEquals(listOf("right-turn-stop", "school-zone", "emergency-yield"), catalog.rotationFor(false).map { it.id })
        // The self-made right-turn briefing covers the PSA's topic, so only one of the two is in a rotation.
        assertFalse(catalog.rotationFor(true).any { it.id == "right-turn-stop" })
    }

    @Test fun rotationHasThreeCreatives() {
        assertEquals(3, catalog.rotation.size)
        assertEquals(3, catalog.rotationFor(false).size)
        assertEquals(catalog.rotation, catalog.rotationFor(catalog.INCLUDE_PSA))
        assertEquals(catalog.rotation.size, catalog.rotation.map { it.id }.toSet().size)
    }

    @Test fun everyCreativeOfEitherRotationIsAVideo() {
        // Every ticket plays a silent clip in the same format as the PSA: the app's caption pill and source line over it.
        (catalog.rotationFor(true) + catalog.rotationFor(false)).forEach { assertIs<VideoCreative>(it, it.id) }
    }

    @Test fun selfMadeVideosPlayTheirOwnClips() {
        val assets = catalog.rotationFor(false).associate { it.id to (it as VideoCreative).asset }
        assertEquals(
            mapOf(
                "right-turn-stop" to "creatives/brief-right-turn.mp4",
                "school-zone" to "creatives/brief-school-zone.mp4",
                "emergency-yield" to "creatives/brief-emergency-yield.mp4",
            ),
            assets,
        )
    }

    @Test fun selfMadeVideosCaptionTheirCardSentencesVerbatim() {
        catalog.publicService.forEach { b ->
            val video = catalog.rotationFor(false).first { it.id == b.id }
            assertIs<VideoCreative>(video)
            assertEquals(b.cards.map { it.line }, video.captions.map { it.text }, b.id)
            // One cue per card on the card's own clock: 4 s + 4 s + 4 s + 3 s.
            assertEquals(listOf(0L, 4_000L, 8_000L, 12_000L), video.captions.map { it.startMs }, b.id)
            assertTrue(video.captions.last().text.startsWith("자체 제작 · 도로교통법"), b.id)
        }
    }

    @Test fun ticketsCycleThroughTheRotation() {
        assertEquals(catalog.rotation.map { it.id }, (0 until 3).map { catalog.forTicket(it).id })
        assertEquals(catalog.rotation[0].id, catalog.forTicket(3).id)
        assertEquals(catalog.rotation[1].id, catalog.forTicket(7).id)
        (0 until 9).forEach { assertEquals(catalog.rotation[it % 3].title, catalog.titleFor(it), "ticket $it") }
    }

    @Test fun videoCreativeMirrorsItsBriefing() {
        val creative = catalog.forTicket(1)
        assertIs<VideoCreative>(creative)
        val briefing = catalog.publicService.first { it.id == "school-zone" }
        assertEquals(briefing.id, creative.id)
        assertEquals(briefing.title, creative.title)
        assertEquals(briefing.basis, creative.source)
        assertEquals("creatives/brief-school-zone.mp4", creative.asset)
        // Its cards stand in if the clip cannot be shown.
        assertSame(briefing, creative.standIn)
    }

    @Test fun onlySelfMadeVideosHaveCardsToStandIn() {
        assertNull(catalog.psa.standIn)
        catalog.rotationFor(false).forEach { creative ->
            val video = creative as VideoCreative
            assertSame(catalog.publicService.first { it.id == video.id }, assertNotNull(video.standIn))
        }
    }

    @Test fun everyVideoIsBundledFifteenSecondsLongAtItsDeclaredSize() {
        // The JVM test runs in the app module (see ProductWordingTest), so the bundled assets are on disk here.
        val videos = (catalog.rotationFor(true) + catalog.rotationFor(false)).filterIsInstance<VideoCreative>().distinctBy { it.asset }
        assertEquals(4, videos.size)
        videos.forEach { video ->
            // The PSA clip sits in its own source set (src/psa/), packaged only with the build switch.
            val file = File(if (video === catalog.psa) "src/psa/assets" else "src/main/assets", video.asset)
            // src/psa/ is copied in only for a PSA build (see build.gradle.kts); without it there is no clip to check.
            if (video === catalog.psa && !file.exists()) return@forEach
            assertTrue(file.isFile, "missing ${file.path}")
            assertTrue(file.length() in 1..2_000_000L, "${video.asset}: ${file.length()} bytes")
            val facts = Mp4.read(file)
            assertEquals(catalog.DURATION_MS, facts.durationMs, video.asset)
            assertEquals(video.width to video.height, facts.width to facts.height, video.asset)
        }
    }

    @Test fun thePsaShipsOnlyFromItsOwnSourceSet() {
        // Nothing of the third-party excerpt is in the source sets every build packages; build.gradle.kts adds
        // src/psa/ only with -Pkilomoa.includePsa=true.
        assertFalse(File("src/main/assets", catalog.psa.asset).exists())
        assertFalse(File("src/home/res/drawable-nodpi/thumb_psa_right_turn.webp").exists())
    }

    @Test fun negativeIndexFallsBackToTheFirstCreativeWithoutReplay() {
        assertEquals(catalog.rotation[0].id, catalog.forTicket(-1).id)
        assertEquals(catalog.rotation[0].id, catalog.forTicket(-7).id)
        assertEquals(catalog.rotation[0].title, catalog.titleFor(-3))
        assertFalse(catalog.isReplay(-1))
    }

    @Test fun cardLinesStayShortAndCiteBasis() {
        catalog.publicService.forEach { b ->
            b.cards.forEach { assertTrue(it.line.length <= 40, it.line) }
            assertTrue(b.basis.startsWith("자체 제작 · 근거: 도로교통법"))
        }
    }

    @Test fun lastCardOfEachPublicBriefingIsTheBasisCard() {
        catalog.publicService.forEach { b ->
            val last = b.cards.last()
            assertEquals(3f, last.seconds, b.id)
            assertEquals(R.drawable.ic_brief_check, last.icon, b.id)
            assertTrue(last.line.startsWith("자체 제작 · 도로교통법"), last.line)
        }
    }

    @Test fun cardAtFollowsTheTimelineAndClampsAtBothEnds() {
        val b = catalog.publicService[0]
        assertSame(b.cards[0], catalog.cardAt(b, -500))
        assertSame(b.cards[0], catalog.cardAt(b, 0))
        assertSame(b.cards[0], catalog.cardAt(b, 3_999))
        assertSame(b.cards[1], catalog.cardAt(b, 4_000))
        assertSame(b.cards[2], catalog.cardAt(b, 8_000))
        assertSame(b.cards[3], catalog.cardAt(b, 12_000))
        assertSame(b.cards[3], catalog.cardAt(b, 14_999))
        assertSame(b.cards[3], catalog.cardAt(b, 15_000))
        assertSame(b.cards[3], catalog.cardAt(b, 99_000))
    }

    @Test fun replayStartsAfterOneFullRotation() {
        assertFalse(catalog.isReplay(0)); assertFalse(catalog.isReplay(2))
        assertTrue(catalog.isReplay(3)); assertTrue(catalog.isReplay(9))
    }

    @Test fun selfMadeCardsCarryNoThirdPartyCreditsOrFines() {
        // Scope: the self-made briefing cards only (content rules, 2026-11-08). The PSA names its agency in
        // `source` on purpose and is checked by psaIsTheRightTurnExcerptWithFiveCaptions.
        val forbidden = listOf("경찰", "공단", "제공", "감수", "협력", "과태료", "범칙금", "벌점", "유튜브", "YouTube")
        catalog.publicService.forEach { b ->
            (b.cards.map { it.line } + b.basis + b.title).forEach { text ->
                forbidden.forEach { word -> assertFalse(text.contains(word), "'$word' in '$text'") }
            }
        }
    }

    @Test fun noMockOrDemoWordingInAnyCreative() {
        // User decision 2026-11-09 (design §2-3): the product reads as a live service, so none of these words appear on screen.
        val banned = listOf("모의", "시연", "예시", "제휴 예정", "실차 미검증")
        val texts = catalog.publicService.flatMap { b -> b.cards.map { it.line } + b.basis + b.title } +
            catalog.psa.captions.map { it.text } + catalog.psa.title + catalog.psa.source +
            (catalog.rotationFor(true) + catalog.rotationFor(false)).flatMap { creative ->
                listOf(creative.title, creative.source) + ((creative as? VideoCreative)?.captions?.map { it.text } ?: emptyList())
            }
        texts.forEach { text -> banned.forEach { word -> assertFalse(text.contains(word), "'$word' in '$text'") } }
    }

    @Test fun psaIsTheRightTurnExcerptWithFiveCaptions() {
        val psa = catalog.psa
        assertEquals("psa-right-turn", psa.id)
        assertEquals("올바른 우회전 방법은?", psa.title)
        assertEquals("creatives/psa-right-turn.mp4", psa.asset)
        assertEquals("경기북부경찰청 · 올바른 우회전 방법은?", psa.source)
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L), psa.captions.map { it.startMs })
        assertEquals("① 전방 차량 신호가 적색이면 반드시 일시정지", psa.captions[0].text)
        assertEquals("보행자가 건너고 있거나 건너려 한다면 기다렸다가 우회전", psa.captions[1].text)
        assertEquals("② 우회전 전용 신호기가 있으면 신호에 따라 우회전", psa.captions[2].text)
        assertEquals("③ 차량·횡단보도 신호가 모두 녹색이면 보행자가 없을 때 서행하며 우회전", psa.captions[3].text)
        assertEquals("모두의 안전을 위해 신호를 꼭 지켜주세요", psa.captions[4].text)
        // Every cue starts inside the 15 s ticket; captions themselves name no agency, fines or grace periods.
        assertTrue(psa.captions.all { it.startMs in 0 until catalog.DURATION_MS })
        listOf("경찰", "과태료", "범칙금", "계도기간", "유튜브", "YouTube").forEach { word ->
            psa.captions.forEach { c -> assertFalse(c.text.contains(word), "'$word' in '${c.text}'") }
        }
    }

    @Test fun iconsCoverTheNineBriefingGlyphs() {
        val expected = setOf(
            R.drawable.ic_brief_stop, R.drawable.ic_brief_turn_right, R.drawable.ic_brief_pedestrian, R.drawable.ic_brief_school,
            R.drawable.ic_brief_speed_30, R.drawable.ic_brief_no_parking, R.drawable.ic_brief_siren, R.drawable.ic_brief_yield,
            R.drawable.ic_brief_check,
        )
        assertEquals(9, expected.size)
        val used = catalog.publicService.flatMap { b -> b.cards.map { it.icon } }.toSet()
        assertEquals(expected, used)
    }

    /** The two facts of an mp4 the player relies on: the movie's length and the picture's size (mvhd, tkhd). */
    private data class Mp4(val durationMs: Long, val width: Int, val height: Int) {
        companion object {
            fun read(file: File): Mp4 = RandomAccessFile(file, "r").use { f ->
                var durationMs = -1L
                var width = -1
                var height = -1
                fun walk(start: Long, end: Long) {
                    var at = start
                    while (at + 8 <= end) {
                        f.seek(at)
                        var size = f.readInt().toLong() and 0xFFFFFFFFL
                        val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                        var body = at + 8
                        if (size == 1L) { size = f.readLong(); body += 8 } else if (size == 0L) size = end - at
                        when (type) {
                            "moov", "trak" -> walk(body, at + size)
                            "mvhd" -> {
                                f.seek(body)
                                val v1 = f.readInt() ushr 24 == 1
                                f.skipBytes(if (v1) 16 else 8)
                                val scale = f.readInt().toLong()
                                val units = if (v1) f.readLong() else f.readInt().toLong() and 0xFFFFFFFFL
                                durationMs = units * 1_000 / scale
                            }
                            "tkhd" -> {
                                f.seek(at + size - 8)
                                width = f.readInt() ushr 16
                                height = f.readInt() ushr 16
                            }
                        }
                        at += size
                    }
                }
                walk(0, f.length())
                Mp4(durationMs, width, height)
            }
        }
    }
}
