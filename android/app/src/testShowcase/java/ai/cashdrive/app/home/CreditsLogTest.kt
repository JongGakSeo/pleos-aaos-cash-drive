package ai.cashdrive.app.home

import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * The credits log (plan C1, design §13-8): the host writes when each ticket was credited, the core stays
 * as it is. Today's points come from the credit times on the device's calendar day, with the day boundary
 * at local midnight; a ticket is logged once and the log keeps its newest entries.
 */
class CreditsLogTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private class MemoryStore : CreditsLog.Store {
        var saved: List<Pair<String, Long>> = emptyList()
        var saves = 0
        override fun load() = saved
        override fun save(entries: List<Pair<String, Long>>) { saved = entries; saves++ }
    }

    private fun log(store: MemoryStore = MemoryStore(), zone: ZoneId = seoul) = CreditsLog(store) { zone }

    @Test fun todayPointsSumOnlyThatDay() {
        val log = log()
        log.record("t-welcome-01", at("2026-11-08T23:59:59.999+09:00"))
        log.record("t-20261109-01", at("2026-11-09T00:00:00+09:00"))
        log.record("t-20261109-02", at("2026-11-09T23:59:59.999+09:00"))
        log.record("t-20261110-01", at("2026-11-10T00:00:00+09:00"))
        assertEquals(10, log.todayPoints("20261108"))
        assertEquals(20, log.todayPoints("20261109"))
        assertEquals(10, log.todayPoints("20261110"))
        assertEquals(0, log.todayPoints("20261107"))
    }

    @Test fun theDayBoundaryIsLocalMidnight() {
        // 15:30 UTC on the 8th is 00:30 on the 9th in Seoul.
        val instant = at("2026-11-08T15:30:00Z")
        val seoulLog = log(zone = seoul).apply { record("t-1", instant) }
        val utcLog = log(zone = ZoneId.of("UTC")).apply { record("t-1", instant) }
        assertEquals(10, seoulLog.todayPoints("20261109"))
        assertEquals(0, seoulLog.todayPoints("20261108"))
        assertEquals(10, utcLog.todayPoints("20261108"))
    }

    @Test fun pointsFollowThePointsPerTicket() {
        val log = log().apply {
            record("t-1", at("2026-11-09T09:00:00+09:00"))
            record("t-2", at("2026-11-09T10:00:00+09:00"))
        }
        assertEquals(30, log.todayPoints("20261109", pointsPerTicket = 15))
    }

    @Test fun aTicketIsLoggedOnceWithItsFirstTime() {
        val store = MemoryStore()
        val log = log(store)
        log.record("t-1", 1_000)
        log.record("t-1", 2_000)
        assertEquals(listOf("t-1" to 1_000L), log.entries())
        assertEquals(1, store.saves)
    }

    @Test fun entriesKeepCreditOrderAndSurviveANewLog() {
        val store = MemoryStore()
        log(store).apply { record("t-1", 1_000); record("t-2", 3_000); record("t-3", 2_000) }
        assertEquals(listOf("t-1" to 1_000L, "t-2" to 3_000L, "t-3" to 2_000L), log(store).entries())
    }

    @Test fun malformedEntriesAreIgnored() {
        val log = log()
        log.record("", 1_000)
        log.record("t-1", -5)
        assertEquals(emptyList(), log.entries())
    }

    @Test fun theLogKeepsItsNewestEntries() {
        val store = MemoryStore()
        val log = CreditsLog(store, max = 3) { seoul }
        listOf("t-1", "t-2", "t-3", "t-4").forEachIndexed { i, id -> log.record(id, 1_000L * (i + 1)) }
        assertEquals(listOf("t-2" to 2_000L, "t-3" to 3_000L, "t-4" to 4_000L), log.entries())
    }
}
