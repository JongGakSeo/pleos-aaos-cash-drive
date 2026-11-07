package ai.cashdrive.app.session

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.core.playback.*

/** UI v2 lifecycle bridge. Actual video/audio focus belong to Claude UI. Explicit LOCAL_MOCK only. */
class PlaybackSessionHost(context: Context, accountId: String) : AutoCloseable {
    private val context = context.applicationContext
    private val store: SqlitePlaybackAccountStore
    private val session: MockPlaybackSession
    private var source: CarVehicleStatusSource? = null
    private var closed = false
    val ui: PlaybackSession get() = session
    val speedPermissionGranted: Boolean get() = context.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    init {
        check(BuildConfig.LOCAL_MOCK) { "This build does not enable LOCAL_MOCK sessions" }
        store = SqlitePlaybackAccountStore(context)
        session = MockPlaybackSession(accountId, store)
    }
    fun start() {
        check(!closed)
        if (!BuildConfig.DEMO_SIGNALS && source == null) {
            source = CarVehicleStatusSource(context, session::updateVehicle).also { it.start() }
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
        session.updateVehicle(VehicleStatus())
    }
    override fun close() {
        if (closed) return
        stop(); session.close(); store.close(); closed = true
    }
    /** Explicit demo fixture, rejected by emu/vehicle; not an authoritative vehicle callback. */
    fun updateMockVehicle(status: VehicleStatus) { check(BuildConfig.DEMO_SIGNALS && !closed); session.updateVehicle(status) }
    fun enqueueMockAd(adId: String, durationMs: Long = 15_000): PlaybackResult {
        check(BuildConfig.DEMO_SIGNALS && !closed); return session.enqueueMockAd(adId, durationMs)
    }
    fun beginMockTrip(tripId: String): PlaybackResult {
        check(BuildConfig.DEMO_SIGNALS && !closed); return session.beginMockTrip(tripId)
    }
    companion object { const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED" }
}
