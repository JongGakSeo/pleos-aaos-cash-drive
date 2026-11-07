package ai.cashdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.app.session.PreferencesDemoAccountStore
import ai.cashdrive.core.session.*
import ai.cashdrive.core.safety.SafetyState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** APK local MOCK path through real SharedPreferences, never a provider/payment test. */
@RunWith(AndroidJUnit4::class)
class SessionPersistenceTest {
    @Test fun accountThemeIsolationAndLostReplyKeepOneDurableMockCredit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PreferencesDemoAccountStore(context)
        val a = "session-test-a-${UUID.randomUUID()}"
        val b = "session-test-b-${UUID.randomUUID()}"
        try {
            val first = MockCashDriveSession(a, store)
            assertEquals(SafetyState.UNKNOWN, first.state.safetyState)
            first.updateSafety(SafetyState.PARKED_ALLOWED)
            first.addMockDistance("synthetic-ticket")
            first.startAd("synthetic-ticket")
            first.completeAd("synthetic-ticket")
            first.receiveLater("synthetic-ticket")
            assertEquals(0, first.state.confirmedPoints)
            first.selectTheme("mining")
            first.confirm("synthetic-ticket") // simulate lost reply by discarding result and instance
            val restored = MockCashDriveSession(a, PreferencesDemoAccountStore(context))
            assertEquals("mining", restored.state.themeId)
            assertEquals(10, restored.state.confirmedPoints)
            restored.updateSafety(SafetyState.PARKED_ALLOWED)
            repeat(4) { assertEquals(SessionCode.OK, restored.confirm("synthetic-ticket").code) }
            assertEquals(10, restored.state.confirmedPoints)
            val other = MockCashDriveSession(b, store)
            other.updateSafety(SafetyState.PARKED_ALLOWED)
            assertEquals("horse", other.state.themeId)
            assertEquals(0, other.state.confirmedPoints)
            assertEquals(SessionCode.UNKNOWN_TICKET, other.confirm("synthetic-ticket").code)
            other.selectTheme("mine")
            assertEquals("mining", MockCashDriveSession(a, store).state.themeId)
            other.selectTheme("gpu")
            assertEquals("horse", MockCashDriveSession(b, store).state.themeId)
        } finally {
            context.getSharedPreferences("cash_drive_session_v1", 0).edit().remove("account:$a").remove("account:$b").commit()
        }
    }
}
