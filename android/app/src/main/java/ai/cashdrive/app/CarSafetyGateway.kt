package ai.cashdrive.app

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.cashdrive.core.safety.GearEvidence
import ai.cashdrive.core.safety.SafetyReducer
import ai.cashdrive.core.safety.SafetySnapshot

/** Read-only callbacks, scoped to one connection epoch, delivered on the main thread. */
class CarSafetyGateway(context: Context, private val reducer: SafetyReducer,
    private val onSnapshot: (SafetySnapshot) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null
    private var propertyListener: CarPropertyManager.CarPropertyEventCallback? = null
    private var active = false
    private var epoch = 0L
    private var sequence = 0L
    private var lastGearTimestamp = -1L

    fun start() {
        if (active) return
        active = true
        connect()
    }
    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        invalidate()
        release()
    }
    private fun invalidate() {
        epoch = maxOf(epoch, reducer.current().generation) + 1
        sequence = 0
        lastGearTimestamp = -1L
        onSnapshot(reducer.invalidate(epoch))
    }
    private fun connect() {
        if (!active) return
        invalidate()
        release()
        val registrationEpoch = epoch
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                main.post {
                    if (!active || registrationEpoch != epoch) return@post
                    if (ready) attach(connected, registrationEpoch) else retry(registrationEpoch)
                }
            }
        } catch (_: RuntimeException) { retry(registrationEpoch) }
    }
    private fun retry(expectedEpoch: Long) {
        if (!active || expectedEpoch != epoch) return
        invalidate()
        release()
        val retryEpoch = epoch
        main.postDelayed({ if (active && epoch == retryEpoch) connect() }, 2_000)
    }
    private fun attach(connected: Car, registrationEpoch: Long) {
        try {
            ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            val uxManager = checkNotNull(ux)
            val propertyManager = checkNotNull(properties)
            onSnapshot(reducer.onConnected(epoch, ++sequence))
            val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener {
                main.post {
                    if (active && epoch == registrationEpoch) {
                        try {
                            // Public API 34 exposes no UXR timestamp. Re-read current state so a queued
                            // old event cannot overwrite a newer authoritative restriction snapshot.
                            val current = checkNotNull(uxManager.currentCarUxRestrictions)
                            onSnapshot(reducer.onUxRestrictions(epoch, ++sequence,
                                current.isRequiresDistractionOptimization, current.activeRestrictions))
                        } catch (_: RuntimeException) { retry(registrationEpoch) }
                    }
                }
            }
            uxManager.registerListener(uxListener)
            // Registration reports future changes; explicitly seed the authoritative current snapshot.
            uxManager.currentCarUxRestrictions?.let(uxListener::onUxRestrictionsChanged)
            if (propertyManager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null) return
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
                    main.post {
                        if (!active || epoch != registrationEpoch) return@post
                        if (value.timestamp < lastGearTimestamp) return@post
                        // Unavailable is also an ordered sample: an older available P event
                        // must never reopen the gate after a newer unavailable sample.
                        lastGearTimestamp = value.timestamp
                        if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                            onSnapshot(reducer.onGear(epoch, ++sequence, GearEvidence.UNKNOWN))
                            return@post
                        }
                        val evidence = when (value.value as? Int) {
                            VehicleGear.GEAR_PARK -> GearEvidence.PARK
                            null, VehicleGear.GEAR_UNKNOWN -> GearEvidence.UNKNOWN
                            else -> GearEvidence.NOT_PARK
                        }
                        onSnapshot(reducer.onGear(epoch, ++sequence, evidence))
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (propertyId != VehiclePropertyIds.GEAR_SELECTION) return
                    main.post {
                        if (active && epoch == registrationEpoch)
                            onSnapshot(reducer.onGear(epoch, ++sequence, GearEvidence.UNKNOWN))
                    }
                }
            }
            propertyListener = listener
            if (!propertyManager.registerCallback(listener, VehiclePropertyIds.GEAR_SELECTION,
                    CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                onSnapshot(reducer.onGear(epoch, ++sequence, GearEvidence.UNKNOWN))
            }
        } catch (_: RuntimeException) { retry(registrationEpoch) }
    }
    private fun release() {
        runCatching { ux?.unregisterListener() }
        propertyListener?.let { listener -> runCatching { properties?.unregisterCallback(listener) } }
        propertyListener = null
        ux = null
        properties = null
        runCatching { car?.disconnect() }
        car = null
    }
}
