package ai.cashdrive.app

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.app.session.CarVehicleStatusSource
import ai.cashdrive.core.playback.VehicleState
import ai.cashdrive.core.playback.VehicleStatus
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Actual public AAOS reads, no MainActivity/renderer/mock vehicle input. Harness owns console. */
@RunWith(AndroidJUnit4::class)
class VehicleReadEvidenceTest {
    private fun waitFor(label: String, seen: List<VehicleStatus>, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Vehicle v2 timeout: $label; latest=${seen.lastOrNull()}")
    }

    @Test fun deniedPermissionNeverAllowsPlayback() {
        assumeFalse(BuildConfig.DEMO_SIGNALS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(CarVehicleStatusSource.SPEED_PERMISSION))
        val seen = CopyOnWriteArrayList<VehicleStatus>()
        val source = CarVehicleStatusSource(context) { seen += it }
        try {
            inst.runOnMainSync { source.start() }
            waitFor("connected but denied", seen) { seen.lastOrNull()?.connected == true }
            assertTrue(seen.all { !it.playbackAllowed && it.vehicleState == VehicleState.UNKNOWN })
            assertTrue(seen.all { !it.speedPermissionGranted })
        } finally {
            inst.runOnMainSync { source.close() }
            File(context.filesDir, "vehicle-denied.txt").writeText(seen.joinToString("\n"))
        }
    }

    @Test fun actualReadOnlySignalsNoVideoAndCarProcessReconnect() {
        assumeFalse(BuildConfig.DEMO_SIGNALS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(CarVehicleStatusSource.SPEED_PERMISSION))
        val seen = CopyOnWriteArrayList<VehicleStatus>()
        val source = CarVehicleStatusSource(context) { seen += it }
        fun marker(value: String) { File(context.filesDir, "vehicle-evidence-stage").writeText(value) }
        try {
            marker("starting")
            inst.runOnMainSync { source.start() }
            assertTrue(seen.any { it.vehicleState == VehicleState.UNKNOWN && !it.playbackAllowed })
            waitFor("actual speed 0/P/UXR", seen) { seen.lastOrNull()?.let {
                it.vehicleState == VehicleState.STOPPED_PARKED && it.speedKph == 0.0 && it.playbackAllowed
            } == true }
            marker("parked")
            waitFor("actual NO_VIDEO overrides parked speed 0", seen) { seen.lastOrNull()?.let {
                it.activeRestrictions?.and(VehicleStatus.NO_VIDEO) != 0 &&
                    it.activeRestrictions != null && !it.playbackAllowed
            } == true }
            marker("restricted")
            waitFor("actual UXR recovery", seen) { seen.lastOrNull()?.playbackAllowed == true }
            val beforeFault = seen.size
            marker("recovered")
            waitFor("disconnect invalidates", seen) { seen.drop(beforeFault).any {
                it.vehicleState == VehicleState.UNKNOWN && !it.playbackAllowed
            } }
            marker("disconnected")
            waitFor("reconnected speed/P/UXR", seen) { seen.lastOrNull()?.playbackAllowed == true }
            marker("passed")
        } finally {
            inst.runOnMainSync { source.close() }
            assertFalse(seen.last().playbackAllowed)
            File(context.filesDir, "vehicle-evidence.txt").writeText(seen.joinToString("\n"))
        }
    }
}
