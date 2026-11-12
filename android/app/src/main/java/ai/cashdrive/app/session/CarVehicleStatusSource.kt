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
import android.os.SystemClock
import android.util.Log
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.core.playback.*
import kotlin.math.abs

/**
 * Public, read-only AAOS adapter. Vehicle SDK 2.3.2 removed speed; no hidden APIs or set calls.
 *
 * [onStatus] receives each distinct [VehicleStatus]. [onSample] receives one speed sample per one-second
 * probe (km/h, or null whenever speed evidence is lost) stamped with [SystemClock.elapsedRealtime], always
 * after the status it was read with, so the session already knows whether the sample is admissible.
 * Status changes are deduplicated; samples are not, because the trip meter integrates them over time.
 * Both callbacks run on the main thread.
 */
class CarVehicleStatusSource(
    context: Context,
    private val onStatus: (VehicleStatus) -> Unit,
    private val onSample: (speedKph: Double?, elapsedMs: Long) -> Unit,
) : AutoCloseable {
    /** Status-only form used by the evidence tests; speed samples are discarded. */
    constructor(context: Context, onStatus: (VehicleStatus) -> Unit) : this(context, onStatus, { _, _ -> })

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val readerThread = HandlerThread("CashDriveVehicleRead").apply { start() }
    private val reader = Handler(readerThread.looper)
    private val reducer = VehicleSignalReducer()
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null
    private var listener: CarPropertyManager.CarPropertyEventCallback? = null
    private var chargeListener: CarPropertyManager.CarPropertyEventCallback? = null
    /** Charge port properties this vehicle exposes for reading; empty keeps the merged value unknown. */
    private var chargeProperties: List<Int> = emptyList()
    private val chargeConnected = ChargeSignal()
    private val chargeOpen = ChargeSignal()
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
    /** New epoch: the status is unknown and there is no speed evidence, so the session's integration window closes. */
    private fun invalidate() {
        publish(reducer.reset(++epoch))
        onSample(null, SystemClock.elapsedRealtime())
    }

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
            subscribeChargePort(manager, expected)
            probe(manager, uxManager, expected)
        } catch (_: RuntimeException) { retry(expected) }
    }

    private fun readUx(manager: CarUxRestrictionsManager, expected: Long) {
        try {
            val value = checkNotNull(manager.currentCarUxRestrictions)
            publish(reducer.onUxRestrictions(expected, value.isRequiresDistractionOptimization, value.activeRestrictions))
        } catch (_: RuntimeException) { retry(expected) }
    }

    /**
     * EV charge port, informational only (CAR_ENERGY_PORTS is a normal permission). Both AAOS properties are
     * read because the emulator's console card drives EV_CHARGE_PORT_OPEN while vehicles report
     * EV_CHARGE_PORT_CONNECTED. A missing, unreadable or unregistrable property stays unknown (null) for
     * this connection; unlike speed and gear it never triggers a reconnect.
     */
    private fun subscribeChargePort(manager: CarPropertyManager, expected: Long) {
        if (context.checkSelfPermission(CHARGE_PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        chargeProperties = CHARGE_PROPERTIES.filter { property ->
            runCatching { manager.getCarPropertyConfig(property)?.let { (it.access and 1) != 0 } == true }.getOrDefault(false)
        }
        if (chargeProperties.isEmpty()) return
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId !in CHARGE_PROPERTIES) return
                main.post { if (current(expected)) applyCharge(expected, value) }
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                if (propertyId !in CHARGE_PROPERTIES) return
                main.post { if (current(expected)) updateCharge(expected, propertyId, SystemClock.elapsedRealtimeNanos(), null) }
            }
        }
        chargeListener = callback
        // Registration failure is not an error path: the one-second probe still reads the property.
        chargeProperties.forEach { property ->
            runCatching { manager.registerCallback(callback, property, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
        }
    }

    /** Read-only health/current-value probe on a worker, only while the host is started.
     * Listener events provide immediate transitions; this detects permission/service/read failures and
     * supplies the once-a-second speed sample the trip meter integrates.
     */
    private fun probe(manager: CarPropertyManager, uxManager: CarUxRestrictionsManager, expected: Long) {
        val chargeToRead = chargeProperties
        reader.post {
            try {
                if (!permitted()) throw SecurityException("Speed permission unavailable")
                val speed = manager.getProperty<Float>(SPEED, 0)
                val gear = manager.getProperty<Int>(GEAR, 0)
                val sampledAt = SystemClock.elapsedRealtime()
                // A failed charge read (SecurityException, unavailable) only makes that property unknown.
                val charge = chargeToRead.map { property -> property to runCatching { manager.getProperty<Boolean>(property, 0) }.getOrNull() }
                main.post {
                    if (!current(expected)) return@post
                    if (!permitted() || speed.status != CarPropertyValue.STATUS_AVAILABLE ||
                        gear.status != CarPropertyValue.STATUS_AVAILABLE) { retry(expected); return@post }
                    applyValue(expected, speed)
                    applyValue(expected, gear)
                    readUx(uxManager, expected)
                    charge.forEach { (property, value) ->
                        if (value != null) applyCharge(expected, value)
                        else updateCharge(expected, property, SystemClock.elapsedRealtimeNanos(), null)
                    }
                    if (!current(expected)) return@post
                    // Status went out above; the sample follows under that status (the session admits samples
                    // only while connected with the speed permission), stamped at read time.
                    onSample(kph(speed.value as? Float), sampledAt)
                    main.postDelayed({ if (current(expected)) probe(manager, uxManager, expected) }, 1_000)
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

    private fun applyCharge(expected: Long, value: CarPropertyValue<*>) = updateCharge(expected, value.propertyId,
        value.timestamp, if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value as? Boolean else null)

    /** Merge: either property true means a cable; both known false means none; anything else is unknown. */
    private fun updateCharge(expected: Long, property: Int, timestampNanos: Long, value: Boolean?) {
        val signal = when (property) { CHARGE_CONNECTED -> chargeConnected; CHARGE_OPEN -> chargeOpen; else -> return }
        if (timestampNanos < signal.timestamp) return
        signal.timestamp = timestampNanos; signal.value = value
        val merged = when {
            chargeConnected.value == true || chargeOpen.value == true -> true
            chargeConnected.value == false && chargeOpen.value == false -> false
            else -> null
        }
        publish(reducer.onChargePort(expected, maxOf(chargeConnected.timestamp, chargeOpen.timestamp), merged))
    }

    /** VHAL speed is m/s and negative in reverse; the meter takes the km/h magnitude, as the reducer does. */
    private fun kph(metersPerSecond: Float?) = metersPerSecond?.takeIf { it.isFinite() }?.let { abs(it.toDouble()) * 3.6 }

    private fun publish(status: VehicleStatus) {
        if (lastPublished == status) return
        lastPublished = status
        if (BuildConfig.DEBUG) Log.i("CashDriveVehicle", "source=AAOS generation=$epoch state=${status.vehicleState} speedKph=${status.speedKph} permission=${status.speedPermissionGranted} connected=${status.connected} active=${status.activeRestrictions} charge=${status.chargePortConnected} playback=${status.playbackAllowed}")
        onStatus(status)
    }
    private fun release() {
        reader.removeCallbacksAndMessages(null)
        runCatching { ux?.unregisterListener() }
        listener?.let { callback -> runCatching { properties?.unregisterCallback(callback) } }
        chargeListener?.let { callback -> runCatching { properties?.unregisterCallback(callback) } }
        listener = null; chargeListener = null; ux = null; properties = null
        chargeProperties = emptyList(); chargeConnected.clear(); chargeOpen.clear()
        runCatching { car?.disconnect() }; car = null
    }

    /** Latest reading of one charge property, ordered by its VHAL timestamp (elapsedRealtimeNanos). */
    private class ChargeSignal {
        var value: Boolean? = null
        var timestamp = -1L
        fun clear() { value = null; timestamp = -1L }
    }

    companion object {
        const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        /** Normal permission declared in the manifest; it covers both charge port properties. */
        const val CHARGE_PERMISSION = "android.car.permission.CAR_ENERGY_PORTS"
        private const val SPEED = VehiclePropertyIds.PERF_VEHICLE_SPEED
        private const val GEAR = VehiclePropertyIds.GEAR_SELECTION
        private const val CHARGE_CONNECTED = VehiclePropertyIds.EV_CHARGE_PORT_CONNECTED   // 0x1120030B
        private const val CHARGE_OPEN = VehiclePropertyIds.EV_CHARGE_PORT_OPEN             // 0x1120030A, the console's Charge Port card
        private val CHARGE_PROPERTIES = listOf(CHARGE_CONNECTED, CHARGE_OPEN)
    }
}
