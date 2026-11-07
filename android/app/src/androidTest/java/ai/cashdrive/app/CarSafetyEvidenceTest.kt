package ai.cashdrive.app

import android.os.SystemClock
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.cashdrive.core.safety.SafetyReducer
import ai.cashdrive.core.safety.SafetySnapshot
import ai.cashdrive.core.safety.SafetyState
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Actual emu Car callbacks; the host harness changes the owned AVD's console and car process. */
@RunWith(AndroidJUnit4::class)
class CarSafetyEvidenceTest {
    @Test fun actualSignalsAndCarProcessReconnectFailClosed() {
        assumeFalse("Use emuDebug for real callbacks", BuildConfig.DEMO_SIGNALS)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val preferences = context.getSharedPreferences("cash_drive_local", 0)
        val previousAccount = preferences.getString("account", null)
        val fixture = "car-evidence-local"
        preferences.edit().putString("account", fixture).putBoolean("dontShowIntro:$fixture", true)
            .putBoolean("skinSelected:$fixture", true).commit()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val seen = CopyOnWriteArrayList<SafetySnapshot>()
        val reducer = SafetyReducer()
        val gateway = CarSafetyGateway(context, reducer) { seen += it }
        fun waitFor(label: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < until) {
                if (condition()) return
                Thread.sleep(100)
            }
            fail("Car evidence timeout: $label; latest=${seen.lastOrNull()}")
        }
        fun marker(name: String) { File(context.filesDir, "car-evidence-stage").writeText(name) }
        try {
            marker("starting")
            inst.runOnMainSync { gateway.start() }
            waitFor("initial UNKNOWN") { seen.any { it.state == SafetyState.UNKNOWN } }
            waitFor("actual PARKED_ALLOWED") { seen.lastOrNull()?.state == SafetyState.PARKED_ALLOWED }
            marker("parked")
            waitFor("actual restricted UXR") { seen.lastOrNull()?.state == SafetyState.RESTRICTED }
            marker("restricted")
            waitFor("actual parked recovery") { seen.lastOrNull()?.state == SafetyState.PARKED_ALLOWED }
            val oldGeneration = seen.last().generation
            marker("recovered")
            waitFor("service disconnected invalidation") { seen.any { it.generation > oldGeneration && it.state == SafetyState.UNKNOWN } }
            marker("disconnected")
            waitFor("new generation receives UXR and P") {
                seen.lastOrNull()?.let { it.generation > oldGeneration && it.state == SafetyState.PARKED_ALLOWED } == true
            }
            marker("passed")
        } finally {
            inst.runOnMainSync { gateway.stop() }
            File(context.filesDir, "car-evidence.txt").writeText(seen.joinToString("\n") { it.toString() })
            preferences.edit().apply {
                if (previousAccount == null) remove("account") else putString("account", previousAccount)
                remove("dontShowIntro:$fixture"); remove("skinSelected:$fixture")
                remove("confirmed:$fixture"); remove("tickets:$fixture")
            }.commit()
        }
    }
}
