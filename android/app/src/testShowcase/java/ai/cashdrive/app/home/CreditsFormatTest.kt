package ai.cashdrive.app.home

import ai.cashdrive.app.R
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The credits page's pure decisions (plan C2, design §8): today's chip, the time of each history row from
 * the credits log, the history rows themselves (newest first, the rotation's titles and icons), where the
 * balance's count-up starts, and the redemption options with their copy.
 */
class CreditsFormatTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test fun todayChipShowsThePointsWithAPlus() {
        assertEquals("오늘 +0", CreditsFormat.today(0))
        assertEquals("오늘 +20", CreditsFormat.today(20))
        assertEquals("오늘 +1,230", CreditsFormat.today(1_230))
        assertEquals("오늘 +0", CreditsFormat.today(-10))
    }

    @Test fun balanceAndCountUseTabularThousands() {
        assertEquals("0", CreditsFormat.balance(0))
        assertEquals("1,230", CreditsFormat.balance(1_230))
        assertEquals("3건", CreditsFormat.entries(3))
        assertEquals("1,000건", CreditsFormat.entries(1_000))
    }

    @Test fun timeLabelNamesTodayAndYesterdayThenTheDate() {
        val now = at("2026-11-09T15:00:00+09:00")
        assertEquals("오늘 14:32", CreditsFormat.timeLabel(at("2026-11-09T14:32:10+09:00"), now, seoul))
        assertEquals("오늘 00:00", CreditsFormat.timeLabel(at("2026-11-09T00:00:00+09:00"), now, seoul))
        assertEquals("어제 23:59", CreditsFormat.timeLabel(at("2026-11-08T23:59:59.999+09:00"), now, seoul))
        assertEquals("11월 7일 09:05", CreditsFormat.timeLabel(at("2026-11-07T09:05:00+09:00"), now, seoul))
        assertEquals("1월 1일 00:10", CreditsFormat.timeLabel(at("2026-01-01T00:10:00+09:00"), now, seoul))
        assertEquals("2025년 12월 31일", CreditsFormat.timeLabel(at("2025-12-31T18:05:00+09:00"), now, seoul))
        // A clock set back after the credit: a later day is named by its date, never "오늘".
        assertEquals("11월 10일 08:00", CreditsFormat.timeLabel(at("2026-11-10T08:00:00+09:00"), now, seoul))
    }

    @Test fun timeLabelFollowsTheLocalDay() {
        // 15:30 UTC on the 8th is 00:30 on the 9th in Seoul.
        val credit = at("2026-11-08T15:30:00Z")
        val now = at("2026-11-09T09:00:00+09:00")
        assertEquals("오늘 00:30", CreditsFormat.timeLabel(credit, now, seoul))
        assertEquals("어제 15:30", CreditsFormat.timeLabel(credit, now, ZoneId.of("UTC")))
    }

    @Test fun historyIsNewestFirstWithTheRotationTitlesAndIcons() {
        val rows = CreditsFormat.history(3, listOf("t-a" to 1_000L, "t-b" to 2_000L, "t-c" to 3_000L))
        assertEquals(listOf(2, 1, 0).map { BriefingCatalog.titleFor(it) }, rows.map { it.title })
        assertEquals(listOf(3_000L, 2_000L, 1_000L), rows.map { it.atMs })
        assertEquals(listOf(2, 1, 0).map { CreditsFormat.iconFor(BriefingCatalog.forTicket(it)) }, rows.map { it.icon })
        assertTrue(rows.all { it.points == CreditsLog.POINTS_PER_TICKET })
    }

    @Test fun ledgerEntriesOlderThanTheLogHaveNoTime() {
        // The log began after three credits: its two times belong to the two newest entries.
        val rows = CreditsFormat.history(5, listOf("t-d" to 4_000L, "t-e" to 5_000L))
        assertEquals(listOf(5_000L, 4_000L, null, null, null), rows.map { it.atMs })
        assertEquals(BriefingCatalog.titleFor(4), rows.first().title)
        assertEquals(BriefingCatalog.titleFor(0), rows.last().title)
    }

    @Test fun aLogLongerThanTheLedgerKeepsOnlyItsNewestTimes() {
        val rows = CreditsFormat.history(2, listOf("x" to 1L, "y" to 2L, "z" to 3L))
        assertEquals(listOf(3L, 2L), rows.map { it.atMs })
    }

    @Test fun historyIsEmptyWithoutCreditsAndKeepsTheNewestUpToTheLimit() {
        assertEquals(emptyList(), CreditsFormat.history(0, listOf("x" to 1L)))
        assertEquals(emptyList(), CreditsFormat.history(-3, emptyList()))
        val rows = CreditsFormat.history(80, emptyList(), limit = 50)
        assertEquals(50, rows.size)
        assertEquals(BriefingCatalog.titleFor(79), rows.first().title)
        assertEquals(BriefingCatalog.titleFor(30), rows.last().title)
    }

    @Test fun rowsCarryTheBriefingsTopicGlyphAndVideosThePlayMark() {
        val byId = BriefingCatalog.rotationFor(true).plus(BriefingCatalog.rotationFor(false)).associateBy { it.id }
        assertEquals(R.drawable.ic_brief_video, CreditsFormat.iconFor(byId.getValue("psa-right-turn")))
        assertEquals(R.drawable.ic_brief_turn_right, CreditsFormat.iconFor(byId.getValue("right-turn-stop")))
        assertEquals(R.drawable.ic_brief_school, CreditsFormat.iconFor(byId.getValue("school-zone")))
        assertEquals(R.drawable.ic_brief_siren, CreditsFormat.iconFor(byId.getValue("emergency-yield")))
        // A self-made briefing's row glyph is always one of its own cards' glyphs, also now that it plays as a video.
        byId.values.forEach { creative ->
            val cards = when (creative) {
                is MotionCreative -> creative.briefing.cards
                is VideoCreative -> creative.standIn?.cards ?: return@forEach
            }
            assertTrue(CreditsFormat.iconFor(creative) in cards.map { it.icon }, creative.id)
        }
        // A briefing without a listed topic falls back to its first card's glyph.
        val other = MotionCreative(Briefing("other", "다른 브리핑", listOf(BriefingCard(R.drawable.ic_brief_yield, "양보해요", 15f)), "근거"))
        assertEquals(R.drawable.ic_brief_yield, CreditsFormat.iconFor(other))
    }

    @Test fun countUpStartsAtTheBalanceBeforeToday() {
        assertEquals(10, CreditsFormat.countFrom(30, 20))
        assertEquals(0, CreditsFormat.countFrom(20, 20))
        // A log ahead of the ledger (a cleared account) never starts below zero.
        assertEquals(0, CreditsFormat.countFrom(10, 40))
        // Nothing credited today: the balance stands still.
        assertEquals(30, CreditsFormat.countFrom(30, 0))
        assertEquals(0, CreditsFormat.countFrom(0, 0))
        assertEquals(0, CreditsFormat.countFrom(-5, 0))
    }

    @Test fun redemptionsAreTheThreeCardsWithTheirOwnIcons() {
        assertEquals(listOf("incar", "bluemembers", "donation"), CreditsFormat.REDEMPTIONS.map { it.id })
        assertEquals(listOf("인카 페이먼트 자동 적립", "블루멤버스 포인트 전환", "교통안전 기부"), CreditsFormat.REDEMPTIONS.map { it.label })
        assertEquals(listOf(R.drawable.ic_pay_card, R.drawable.ic_membership, R.drawable.ic_safety), CreditsFormat.REDEMPTIONS.map { it.icon })
        assertEquals("incar", CreditsFormat.DEFAULT_REDEMPTION)
        assertEquals("donation", CreditsFormat.redemptionOrDefault("donation"))
        assertEquals("incar", CreditsFormat.redemptionOrDefault("unknown"))
        assertEquals("incar", CreditsFormat.redemptionOrDefault(null))
    }

    @Test fun pageCopyReadsAsALiveService() {
        assertEquals("1 크레딧 = 1원", CreditsFormat.RATE)
        assertEquals("다음 정산에 반영 · 매월 1일", CreditsFormat.SETTLEMENT)
        val banned = listOf("모의", "시연", "예시", "제휴 예정", "실차 미검증", "가정", "현금")
        val copy = CreditsFormat.REDEMPTIONS.flatMap { listOf(it.label, it.help) } +
            listOf(CreditsFormat.RATE, CreditsFormat.SETTLEMENT, CreditsFormat.EMPTY_TITLE, CreditsFormat.EMPTY_BODY)
        copy.forEach { text ->
            banned.forEach { word -> assertFalse(text.contains(word), "'$word' in '$text'") }
            assertFalse(text.contains('\u2014'), "em dash in '$text'")
        }
        // Sentences end in the "~해요" tone.
        (CreditsFormat.REDEMPTIONS.map { it.help } + CreditsFormat.EMPTY_TITLE + CreditsFormat.EMPTY_BODY).forEach {
            assertTrue(it.endsWith("요"), it)
        }
    }

    @Test fun theWeekIsTheLastSevenDaysEndingToday() {
        val now = at("2026-11-09T15:00:00+09:00") // a Monday
        val log = listOf(
            "t-1" to at("2026-11-03T08:00:00+09:00"), // Tuesday a week back: still in
            "t-2" to at("2026-11-08T21:00:00+09:00"),
            "t-3" to at("2026-11-08T22:00:00+09:00"),
            "t-4" to at("2026-11-09T09:00:00+09:00"),
            "t-0" to at("2026-11-02T23:59:00+09:00"), // eight days back: out
        )
        val week = CreditsFormat.week(log, now, seoul)
        assertEquals(listOf("화", "수", "목", "금", "토", "일", "오늘"), week.map { it.label })
        assertEquals(listOf(10, 0, 0, 0, 0, 20, 10), week.map { it.points })
        assertTrue(week.last().today)
        assertFalse(week.first().today)
        // Nothing logged: seven empty days, today last.
        assertEquals(List(7) { 0 }, CreditsFormat.week(emptyList(), now, seoul).map { it.points })
    }

    @Test fun theWeeksScaleNeverFlattensSmallDays() {
        // The tallest bar fills the chart, but a quiet week still scales to at least 30 points.
        assertEquals(30, CreditsFormat.weekScale(listOf(0, 0, 10, 0, 0, 0, 20)))
        assertEquals(80, CreditsFormat.weekScale(listOf(0, 80, 10, 0, 0, 0, 20)))
        assertEquals(30, CreditsFormat.weekScale(emptyList()))
    }

    @Test fun settlementIsTheFirstOfNextMonth() {
        val now = at("2026-11-09T15:00:00+09:00")
        assertEquals("12월 1일", CreditsFormat.settlementDate(now, seoul))
        assertEquals(22, CreditsFormat.daysToSettlement(now, seoul))
        assertEquals("D-22", CreditsFormat.dDay(22))
        assertEquals("1월 1일", CreditsFormat.settlementDate(at("2026-12-31T23:00:00+09:00"), seoul))
        assertEquals(1, CreditsFormat.daysToSettlement(at("2026-12-31T23:00:00+09:00"), seoul))
        // This month's points come from the log's times; other months do not count.
        val log = listOf("a" to at("2026-10-31T23:00:00+09:00"), "b" to at("2026-11-01T00:10:00+09:00"), "c" to at("2026-11-09T10:00:00+09:00"))
        assertEquals(20, CreditsFormat.monthPoints(log, now, seoul))
        // How far the month has gone: the 9th of a 30-day month.
        assertEquals(9f / 30f, CreditsFormat.monthProgress(now, seoul), 0.0001f)
    }

    @Test fun theSettlementLineFollowsTheChosenRedemption() {
        assertEquals("이번 달 30 크레딧 · 인카 페이먼트로 자동 적립돼요", CreditsFormat.settlementLine(30, "incar"))
        assertEquals("이번 달 1,200 크레딧 · 블루멤버스 포인트로 전환돼요", CreditsFormat.settlementLine(1_200, "bluemembers"))
        assertEquals("이번 달 0 크레딧 · 교통안전 캠페인에 기부돼요", CreditsFormat.settlementLine(-5, "donation"))
        // An unknown preference reads as the default card.
        assertEquals("이번 달 10 크레딧 · 인카 페이먼트로 자동 적립돼요", CreditsFormat.settlementLine(10, "gone"))
        CreditsFormat.REDEMPTIONS.forEach { assertTrue(it.settles.endsWith("요"), it.settles) }
    }
}
