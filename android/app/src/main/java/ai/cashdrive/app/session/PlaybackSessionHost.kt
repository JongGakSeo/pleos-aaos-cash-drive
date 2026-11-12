package ai.cashdrive.app.session

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.core.playback.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * UI v2 lifecycle bridge. Actual video/audio focus belong to Claude UI. Explicit LOCAL_MOCK only.
 * Vehicle status and one-second speed samples flow from [CarVehicleStatusSource] into the session; the
 * host supplies the device-local day key and the demo-only synthetic drive.
 */
class PlaybackSessionHost(context: Context, accountId: String) : AutoCloseable {
    private val context = context.applicationContext
    private val store: SqlitePlaybackAccountStore
    private val session: MockPlaybackSession
    private var source: CarVehicleStatusSource? = null
    private var closed = false
    /** Virtual clock of injectMockDrive in ms; demo builds have no vehicle source, so it never meets a real sample. */
    private var mockClockMs = 0L
    val ui: PlaybackSession get() = session
    val speedPermissionGranted: Boolean get() = context.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    init {
        check(BuildConfig.LOCAL_MOCK) { "This build does not enable LOCAL_MOCK sessions" }
        store = SqlitePlaybackAccountStore(context)
        // Device-local calendar day (YYYYMMDD): today's totals, the daily cap and ticket ids t-YYYYMMDD-NN.
        session = MockPlaybackSession(accountId, store, dayKey = { LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) })
    }
    fun start() {
        check(!closed)
        if (!BuildConfig.DEMO_SIGNALS && source == null) {
            // The source publishes a status before the sample read with it, so the session can judge the sample.
            source = CarVehicleStatusSource(context, session::updateVehicle) { kph, at -> session.onSpeedSample(kph, at) }
                .also { it.start() }
        }
    }
    /** UI chooses when to show the OS dialog and forwards its result to refreshSpeedPermission. */
    fun requestSpeedPermission(activity: Activity, requestCode: Int) {
        check(!closed)
        if (!BuildConfig.DEMO_SIGNALS && !speedPermissionGranted)
            activity.requestPermissions(arrayOf(SPEED_PERMISSION), requestCode)
    }
    fun refreshSpeedPermission() { if (!closed) source?.refreshSpeedPermission() }
    fun stop() {
        source?.close(); source = null
        // No source, no evidence: unknown status and a closed integration window (also for demo builds, which have no source).
        session.updateVehicle(VehicleStatus())
        session.onSpeedSample(null, SystemClock.elapsedRealtime())
    }
    override fun close() {
        if (closed) return
        stop(); session.close(); store.close(); closed = true
    }
    /** The UI finished the reward reveal. Safe after close: the session answers CLOSED. */
    fun acknowledgeReveal(): PlaybackResult = session.acknowledgeReveal()
    /** Explicit demo fixture, rejected by emu/vehicle; not an authoritative vehicle callback. */
    fun updateMockVehicle(status: VehicleStatus) { check(BuildConfig.DEMO_SIGNALS && !closed); session.updateVehicle(status) }
    fun enqueueMockAd(adId: String, durationMs: Long = 15_000): PlaybackResult {
        check(BuildConfig.DEMO_SIGNALS && !closed); return session.enqueueMockAd(adId, durationMs)
    }
    fun beginMockTrip(tripId: String): PlaybackResult {
        check(BuildConfig.DEMO_SIGNALS && !closed); return session.beginMockTrip(tripId)
    }
    /**
     * Demo fixture only, rejected by emu/vehicle/showcase: feeds the session a synthetic drive of about
     * [meters] at [MOCK_DRIVE_KPH], one sample per virtual second. It is not distance measurement, it
     * leaves the vehicle status alone, and the meter alone decides whether tickets follow (the session
     * still requires its mock status to be connected with the speed permission).
     */
    fun injectMockDrive(meters: Long): PlaybackResult {
        check(BuildConfig.DEMO_SIGNALS && !closed)
        require(meters >= 0)
        // Seconds at 50 km/h that cover the distance, rounded up: meters / (50 / 3.6) = meters * 36 / 500.
        val seconds = ((meters * 36 + 499) / 500).toInt()
        // The first sample only opens the integration window; each following one adds one second of driving.
        var result = session.onSpeedSample(MOCK_DRIVE_KPH, mockTick())
        repeat(seconds) { result = session.onSpeedSample(MOCK_DRIVE_KPH, mockTick()) }
        return result
    }
    private fun mockTick(): Long { mockClockMs += 1_000; return mockClockMs }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        const val MOCK_DRIVE_KPH = 50.0
    }
}
