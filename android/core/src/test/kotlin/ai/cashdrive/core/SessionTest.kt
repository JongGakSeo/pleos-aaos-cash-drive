package ai.cashdrive.core

import ai.cashdrive.core.session.*
import ai.cashdrive.core.safety.SafetyState
import ai.cashdrive.core.rewards.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors

class SessionTest {
    private class Store : DemoAccountStore {
        val records = mutableMapOf<String, DemoAccountRecord>()
        var fail = false
        @Synchronized override fun read(accountId: String) = records[accountId]
        @Synchronized override fun update(accountId: String, change: (DemoAccountRecord?) -> DemoAccountRecord): DemoAccountRecord {
            if (fail) throw IOException("fixture disk error")
            return change(records[accountId]).also { records[accountId] = it }
        }
    }
    private fun parked(store: Store, account: String = "a") = MockCashDriveSession(account, store).also { it.updateSafety(SafetyState.PARKED_ALLOWED) }
    private fun completed(session: MockCashDriveSession, id: String = "ticket") {
        assertEquals(SessionCode.OK, session.addMockDistance(id).code)
        assertEquals(SessionCode.OK, session.startAd(id).code)
        assertEquals(SessionCode.OK, session.completeAd(id).code)
    }

    @Test fun completionAndLaterNeverCreditWithoutExplicitConfirmation() {
        val session = parked(Store())
        completed(session)
        assertEquals(0, session.state.confirmedPoints)
        assertEquals(10, session.state.tripCandidatePoints)
        assertEquals(0, session.state.remainingAdCount)
        assertEquals(1, session.state.awaitingConfirmationCount)
        assertEquals(SessionCode.OK, session.receiveLater("ticket").code)
        assertEquals(0, session.state.confirmedPoints)
        assertEquals(SessionCode.OK, session.confirm("ticket").code)
        assertEquals(10, session.state.confirmedPoints)
        assertEquals(0, session.state.awaitingConfirmationCount)
    }
    @Test fun lostConfirmationReplyAndRepeatedClicksPersistOneCredit() {
        val store = Store(); val session = parked(store); completed(session)
        session.confirm("ticket") // caller loses result after durable write
        val restarted = parked(store)
        repeat(10) { assertEquals(SessionCode.OK, restarted.confirm("ticket").code) }
        assertEquals(10, store.read("a")!!.wallet.confirmedPoints)
        assertEquals(1, store.read("a")!!.wallet.tickets.count { it.status == TicketStatus.CREDITED })
    }
    @Test fun themeRoundTripsAreAccountIsolatedAndNeverChangeRewards() {
        val store = Store(); val a = parked(store); completed(a)
        val before = store.read("a")!!.wallet
        assertEquals(SessionCode.OK, a.selectTheme("mining").code)
        val b = parked(store, "b")
        assertEquals("horse", b.state.themeId)
        assertEquals(0, b.state.confirmedPoints)
        assertTrue(b.state.tickets.isEmpty())
        b.selectTheme("mine")
        assertEquals("mining", parked(store).state.themeId)
        assertEquals("mine", parked(store, "b").state.themeId)
        assertEquals(before, store.read("a")!!.wallet)
        assertEquals(SessionCode.UNKNOWN_TICKET, b.confirm("ticket").code)
        assertEquals(1, a.state.awaitingConfirmationCount)
    }
    @Test fun missingInvalidAndLegacyGpuThemesFallBackToHorse() {
        val store = Store()
        for (value in listOf(null, "", "gpu", "../mine", "MINING")) {
            store.records["a"] = DemoAccountRecord(WalletSnapshot("a", 7, emptyList()), value)
            val a = parked(store)
            assertEquals("horse", a.state.themeId)
            assertEquals("horse", store.read("a")!!.themeId)
            assertEquals(7, a.state.confirmedPoints)
        }
        val a = parked(store); a.selectTheme("mine"); a.selectTheme("invalid")
        assertEquals("horse", a.state.themeId)
    }
    @Test fun safetyRevocationCancelsPlaybackButPreservesCompletedConfirmation() {
        val store = Store(); val session = parked(store)
        session.addMockDistance("first"); session.startAd("first")
        session.updateSafety(SafetyState.RESTRICTED)
        assertEquals(SessionCode.LOCKED, session.completeAd("first").code)
        assertEquals(TicketStatus.PENDING, session.state.tickets.single().status)
        assertEquals(SessionCode.LOCKED, session.selectTheme("mine").code)
        session.updateSafety(SafetyState.PARKED_ALLOWED)
        session.startAd("first"); session.completeAd("first")
        session.updateSafety(SafetyState.UNKNOWN)
        assertEquals(SessionCode.LOCKED, session.confirm("first").code)
        val restarted = MockCashDriveSession("a", store)
        assertEquals(SafetyState.UNKNOWN, restarted.state.safetyState)
        assertEquals(1, restarted.state.awaitingConfirmationCount)
        restarted.updateSafety(SafetyState.PARKED_ALLOWED)
        restarted.confirm("first")
        assertEquals(10, restarted.state.confirmedPoints)
    }
    @Test fun invalidTransitionsAndConcurrentPlaybackDoNotCredit() {
        val session = parked(Store())
        session.addMockDistance("a"); session.addMockDistance("b")
        assertEquals(SessionCode.INVALID_TRANSITION, session.confirm("a").code)
        assertEquals(SessionCode.INVALID_TRANSITION, session.completeAd("a").code)
        session.startAd("a")
        assertEquals(SessionCode.INVALID_TRANSITION, session.startAd("b").code)
        assertEquals(SessionCode.INVALID_TRANSITION, session.confirm("a").code)
        assertEquals(0, session.state.confirmedPoints)
    }
    @Test fun duplicateDistanceAndNextTripDoNotLoseUnconfirmedTickets() {
        val session = parked(Store()); completed(session)
        session.addMockDistance("ticket")
        assertEquals(10, session.state.tripCandidatePoints)
        session.beginMockTrip("next")
        assertEquals(0, session.state.tripCandidatePoints)
        assertEquals(1, session.state.awaitingConfirmationCount)
        session.confirm("ticket")
        assertEquals(10, session.state.confirmedPoints)
    }
    @Test fun storageFailureNeverReportsCreditAndCanBeRetried() {
        val store = Store(); val session = parked(store); completed(session)
        store.fail = true
        assertEquals(SessionCode.STORAGE_ERROR, session.confirm("ticket").code)
        assertEquals(0, session.state.confirmedPoints)
        assertFalse(session.state.storageAvailable)
        store.fail = false; session.refresh(); session.confirm("ticket")
        assertEquals(10, session.state.confirmedPoints)
    }
    @Test fun atomicStorePreventsTwoSessionsDoubleCrediting() {
        val store = Store(); val a = parked(store); completed(a); val b = parked(store)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = listOf(a,b).map { session -> pool.submit<SessionResult> { session.confirm("ticket") } }
            calls.forEach { assertEquals(SessionCode.OK, it.get().code) }
        } finally { pool.shutdownNow() }
        assertEquals(10, store.read("a")!!.wallet.confirmedPoints)
    }
    @Test fun observerGetsSafetyChangesAndCanBeRemoved() {
        val session = MockCashDriveSession("a", Store())
        val states = mutableListOf<SafetyState>()
        val subscription = session.observe { states += it.safetyState }
        session.updateSafety(SafetyState.PARKED_ALLOWED)
        subscription.close(); session.updateSafety(SafetyState.UNKNOWN)
        assertEquals(listOf(SafetyState.UNKNOWN, SafetyState.PARKED_ALLOWED), states)
    }
}
