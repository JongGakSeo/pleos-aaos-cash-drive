package ai.cashdrive.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.app.session.SqlitePlaybackAccountStore
import ai.cashdrive.core.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** No UI/renderer or vehicle injection. Actual Android SQLite, account isolation, and reopen. */
@RunWith(AndroidJUnit4::class)
class PlaybackPersistenceTest {
    @Test fun prepareProcessRestartFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "instrumentation-restart-${System.nanoTime()}"
        SqlitePlaybackAccountStore(context).use { store ->
            val session = MockPlaybackSession(account, store)
            session.updateVehicle(VehicleStatus(VehicleState.STOPPED_PARKED, 0.0, true, true, 0, false))
            assertEquals(PlaybackCode.OK, session.selectTheme("mine").code)
            assertEquals(PlaybackCode.OK, session.onAdCompleted("mock-initial-1").code)
            assertEquals(PlaybackCode.OK, session.onPlaybackPosition("mock-initial-2", 6789).code)
            session.close()
        }
        File(context.filesDir, "playback-restart-fixture.json").writeText(JSONObject()
            .put("account", account).put("beforePid", android.os.Process.myPid()).toString())
    }

    /** Harness force-stops only its leased demo package between prepare and this method. */
    @Test fun verifyAfterRealProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = JSONObject(File(context.filesDir, "playback-restart-fixture.json").readText())
        val beforePid = fixture.getInt("beforePid")
        assertNotEquals(beforePid, android.os.Process.myPid())
        SqlitePlaybackAccountStore(context).use { store ->
            val session = MockPlaybackSession(fixture.getString("account"), store)
            assertEquals(10, session.state.totalPoints)
            assertEquals(2, session.state.remainingAdCount)
            assertEquals("mine", session.state.themeId)
            assertEquals("mock-initial-2", session.state.currentAd!!.adId)
            assertEquals(6789L, session.state.currentAd!!.positionMs)
            assertFalse(session.state.playbackAllowed)
            session.updateVehicle(VehicleStatus(VehicleState.STOPPED_WAITING, 0.0, true, true, 64, true))
            assertEquals(PlaybackCode.OK, session.onAdCompleted("mock-initial-1").code)
            assertEquals(10, session.state.totalPoints)
            session.close()
        }
        File(context.filesDir, "playback-restart-result.json").writeText(JSONObject()
            .put("beforePid", beforePid).put("afterPid", android.os.Process.myPid())
            .put("totalPoints", 10).put("remainingAdCount", 2).put("positionMs", 6789)
            .put("safetyRestored", false).toString())
    }

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
