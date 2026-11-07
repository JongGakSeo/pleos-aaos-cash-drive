package ai.cashdrive.app.session

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.core.playback.*

/** Public, read-only AAOS adapter. Vehicle SDK 2.3.2 removed speed; no hidden APIs or set calls. */
class CarVehicleStatusSource(context: Context, private val onStatus: (VehicleStatus) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val readerThread = HandlerThread("CashDriveVehicleRead").apply { start() }
    private val reader = Handler(readerThread.looper)
    private val reducer = VehicleSignalReducer()
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null
    private var listener: CarPropertyManager.CarPropertyEventCallback? = null
    private var active = false
    private var closed = false
    private var epoch = 0L
    private var lastPublished: VehicleStatus? = null

    fun start() = onMain {
        if (!active && !closed) { active = true; connect() }
    }

    /** Call after the UI's runtime permission result or returning from permission settings. */
    fun refreshSpeedPermission() = onMain { if (active && !closed) connect() }

    fun stop() = onMain {
        active = false
        main.removeCallbacksAndMessages(null)
        invalidate()
        release()
    }

    override fun close() = onMain {
        if (!closed) {
            stop(); closed = true
            reader.removeCallbacksAndMessages(null)
            readerThread.quitSafely()
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
    private fun current(expected: Long) = active && !closed && epoch == expected
    private fun permitted() = context.checkSelfPermission(SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED
    private fun invalidate() { publish(reducer.reset(++epoch)) }

    private fun connect() {
        if (!active || closed) return
        invalidate(); release()
        val expected = epoch
        try {
            // Lifecycle form prevents the platform's default process-kill behavior on service death.
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                main.post { if (current(expected)) { if (ready) attach(connected, expected) else retry(expected) } }
            }
        } catch (_: RuntimeException) { retry(expected) }
    }

    private fun retry(expected: Long) {
        if (!current(expected)) return
        invalidate(); release()
        val retryEpoch = epoch
        main.postDelayed({ if (current(retryEpoch)) connect() }, 2_000)
    }

    private fun attach(connected: Car, expected: Long) {
        try {
            val uxManager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = uxManager
            publish(reducer.onConnected(expected, permitted()))
            val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
                main.post {
                    if (current(expected)) readUx(uxManager, expected)
                }
            }
            uxManager.registerListener(uxListener)
            readUx(uxManager, expected)
            if (!current(expected)) return
            // Never access speed capabilities/properties before the runtime grant.
            if (!permitted()) return
            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = manager
            val speedConfig = manager.getCarPropertyConfig(SPEED)
            val gearConfig = manager.getCarPropertyConfig(GEAR)
            if (speedConfig == null || gearConfig == null ||
                speedConfig.access and 1 == 0 || gearConfig.access and 1 == 0) return
            val callback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.propertyId != SPEED && value.propertyId != GEAR) return
                    main.post {
                        if (!current(expected)) return@post
                        if (!permitted() || value.status != CarPropertyValue.STATUS_AVAILABLE) {
                            retry(expected); return@post
                        }
                        applyValue(expected, value)
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (propertyId == SPEED || propertyId == GEAR) main.post { retry(expected) }
                }
            }
            listener = callback
            if (!manager.registerCallback(callback, SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) ||
                !manager.registerCallback(callback, GEAR, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                retry(expected); return
            }
            probe(manager, uxManager, expected)
        } catch (_: RuntimeException) { retry(expected) }
    }

    private fun readUx(manager: CarUxRestrictionsManager, expected: Long) {
        try {
            val value = checkNotNull(manager.currentCarUxRestrictions)
            publish(reducer.onUxRestrictions(expected, value.isRequiresDistractionOptimization, value.activeRestrictions))
        } catch (_: RuntimeException) { retry(expected) }
    }

    /** Read-only health/current-value probe on a worker, only while the host is started.
     * Listener events provide immediate transitions; this detects permission/service/read failures.
     */
    private fun probe(manager: CarPropertyManager, uxManager: CarUxRestrictionsManager, expected: Long) {
        reader.post {
            try {
                if (!permitted()) throw SecurityException("Speed permission unavailable")
                val speed = manager.getProperty<Float>(SPEED, 0)
                val gear = manager.getProperty<Int>(GEAR, 0)
                main.post {
                    if (!current(expected)) return@post
                    if (!permitted() || speed.status != CarPropertyValue.STATUS_AVAILABLE ||
                        gear.status != CarPropertyValue.STATUS_AVAILABLE) { retry(expected); return@post }
                    applyValue(expected, speed)
                    applyValue(expected, gear)
                    readUx(uxManager, expected)
                    if (current(expected)) main.postDelayed({ if (current(expected)) probe(manager, uxManager, expected) }, 1_000)
                }
            } catch (_: RuntimeException) { main.post { retry(expected) } }
        }
    }

    private fun applyValue(expected: Long, value: CarPropertyValue<*>) {
        if (value.propertyId == SPEED) publish(reducer.onSpeed(expected, value.timestamp, value.value as? Float))
        else publish(reducer.onGear(expected, value.timestamp, when (value.value as? Int) {
            VehicleGear.GEAR_PARK -> ParkSignal.PARK
            VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_REVERSE, VehicleGear.GEAR_DRIVE,
            VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
            VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH,
            VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH -> ParkSignal.NOT_PARK
            else -> ParkSignal.UNKNOWN
        }))
    }

    private fun publish(status: VehicleStatus) {
        if (lastPublished == status) return
        lastPublished = status
        if (BuildConfig.DEBUG) Log.i("CashDriveVehicle", "source=AAOS generation=$epoch state=${status.vehicleState} speedKph=${status.speedKph} permission=${status.speedPermissionGranted} connected=${status.connected} active=${status.activeRestrictions} playback=${status.playbackAllowed}")
        onStatus(status)
    }
    private fun release() {
        reader.removeCallbacksAndMessages(null)
        runCatching { ux?.unregisterListener() }
        listener?.let { callback -> runCatching { properties?.unregisterCallback(callback) } }
        listener = null; ux = null; properties = null
        runCatching { car?.disconnect() }; car = null
    }
    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val SPEED = VehiclePropertyIds.PERF_VEHICLE_SPEED
        private const val GEAR = VehiclePropertyIds.GEAR_SELECTION
    }
}
