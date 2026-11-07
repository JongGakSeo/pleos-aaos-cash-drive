package ai.cashdrive.app.session

import android.content.Context
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.CarSafetyGateway
import ai.cashdrive.core.safety.SafetyReducer
import ai.cashdrive.core.safety.SafetyState
import ai.cashdrive.core.session.CashDriveSession
import ai.cashdrive.core.session.MockCashDriveSession

/**
 * Debug-only integration host for Claude UI. Read/act through ui; bind lifecycle here.
 * Demo uses explicitly synthetic safety. Emu uses real Car callbacks; missing signals lock.
 * Not an OAuth/ad-provider/payment implementation. Do not construct in release builds.
 */
class DemoSessionHost(context: Context, accountId: String) : AutoCloseable {
    private val mock: MockCashDriveSession
    private var gateway: CarSafetyGateway? = null
    private val context = context.applicationContext
    val ui: CashDriveSession get() = mock

    init {
        check(BuildConfig.DEBUG) { "LOCAL_MOCK sessions are debug-only" }
        mock = MockCashDriveSession(accountId, PreferencesDemoAccountStore(context))
    }

    fun start() {
        if (BuildConfig.DEMO_SIGNALS || gateway != null) return
        gateway = CarSafetyGateway(context, SafetyReducer()) { mock.updateSafety(it.state) }.also { it.start() }
    }

    /** Demo fixture only; emu/vehicle variants cannot use synthetic parked permission. */
    fun updateMockSafety(state: SafetyState) {
        check(BuildConfig.DEMO_SIGNALS)
        mock.updateSafety(state)
    }

    fun addMockDistance(ticketId: String, points: Int = 10) = mockInput { mock.addMockDistance(ticketId, points) }
    fun beginMockTrip(tripId: String) = mockInput { mock.beginMockTrip(tripId) }
    private fun <T> mockInput(action: () -> T): T { check(BuildConfig.DEMO_SIGNALS); return action() }

    fun stop() {
        gateway?.stop()
        gateway = null
        mock.updateSafety(SafetyState.UNKNOWN)
    }
    override fun close() = stop()
}
