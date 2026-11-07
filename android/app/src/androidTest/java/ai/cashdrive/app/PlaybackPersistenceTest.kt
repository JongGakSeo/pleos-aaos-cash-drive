package ai.cashdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.app.session.SqlitePlaybackAccountStore
import ai.cashdrive.core.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No UI/renderer or vehicle injection. Actual Android SQLite, account isolation, and reopen. */
@RunWith(AndroidJUnit4::class)
class PlaybackPersistenceTest {
    @Test fun automaticCreditResumeAndAccountIsolationSurviveDatabaseReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "instrumentation-playback-${System.nanoTime()}"
        val parked = VehicleStatus(VehicleState.STOPPED_PARKED, 0.0, true, true, 0, false)
        SqlitePlaybackAccountStore(context).use { store ->
            val a = MockPlaybackSession(account, store)
            a.updateVehicle(parked)
            assertEquals(PlaybackCode.OK, a.selectTheme("mining").code)
            assertEquals(PlaybackCode.OK, a.onAdCompleted("mock-initial-1").code)
            assertEquals(PlaybackCode.OK, a.onAdCompleted("mock-initial-1").code)
            a.updateVehicle(parked.copy(vehicleState = VehicleState.DRIVING, speedKph = 40.0))
            assertEquals(PlaybackCode.OK, a.onPlaybackPosition("mock-initial-2", 4321).code)
            assertEquals(PlaybackCode.LOCKED, a.onAdCompleted("mock-initial-2").code)
            a.close()
            val b = MockPlaybackSession("$account-B", store)
            assertEquals(0, b.state.totalPoints)
            assertEquals("horse", b.state.themeId)
            assertEquals(0L, b.state.currentAd!!.positionMs)
            b.close()
        }
        SqlitePlaybackAccountStore(context).use { store ->
            val restored = MockPlaybackSession(account, store)
            assertFalse(restored.state.playbackAllowed)
            assertEquals(10, restored.state.totalPoints)
            assertEquals(2, restored.state.remainingAdCount)
            assertEquals("mining", restored.state.themeId)
            assertEquals(4321L, restored.state.currentAd!!.positionMs)
            restored.updateVehicle(parked.copy(vehicleState = VehicleState.STOPPED_WAITING,
                activeRestrictions = 64, requiresDistractionOptimization = true))
            assertTrue(restored.state.playbackAllowed)
            assertEquals(PlaybackCode.OK, restored.onAdCompleted("mock-initial-1").code)
            assertEquals(10, restored.state.totalPoints)
            restored.close()
        }
    }
}
