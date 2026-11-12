package ai.cashdrive.app.pleos

import ai.cashdrive.app.BuildConfig
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Read-only view of the Pleos Vehicle SDK (Vehicle 2.3.2). Every value stays null until the Vehicle Service
 * confirmed read access to the capability and delivered it; [odometerSupported] is null until the odometer
 * capability was answered, true when it is readable and false when the check failed or reported no read
 * access. [connected] means the service answered at least one capability query in this session.
 *
 * Tickets never come from these values: speed integration stays the source, and the odometer is for display
 * and the [ai.cashdrive.core.playback.OdometerCrossCheck] log only.
 */
data class PleosVehicleSnapshot(
    val odometerKm: Float? = null,
    val tripMeters: Float? = null,
    val socPercent: Int? = null,
    val chargePortOpen: Boolean? = null,
    val odometerSupported: Boolean? = null,
    val connected: Boolean = false,
)

/**
 * Pleos Vehicle SDK adapter for Odometer (cumulative km, current trip m) and EvBattery (SOC %, charge port).
 * Strictly read-only: capability checks, get* reads and register* listeners, never set*. Every SDK callback
 * is re-posted to the main thread and filtered by connection epoch, so a stale or late answer never leaks.
 *
 * Fail-closed: an initialize exception, an onFailed callback, a synchronous SDK throw or no answer within
 * [ANSWER_TIMEOUT_MS] nulls the affected field (the odometer also becomes unsupported) and schedules exactly
 * one reconnect [RETRY_DELAY_MS] later per [start]. A capability without read access closes the field for good.
 */
class PleosVehicleSource(context: Context, private val onSnapshot: (PleosVehicleSnapshot) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var odometerListener: DistanceDrivenListener? = null
    private var tripListener: DistanceDrivenListener? = null
    private var socListener: EvBatteryStateOfChargeListener? = null
    private var portListener: EvChargePortOpenListener? = null
    private val pending = mutableSetOf<Field>()
    private var active = false
    private var closed = false
    private var retried = false
    private var epoch = 0L
    private var snapshot = PleosVehicleSnapshot()
    private var lastPublished: PleosVehicleSnapshot? = null

    fun start() = onMain {
        if (!active && !closed) { active = true; retried = false; connect() }
    }

    fun stop() = onMain {
        active = false
        main.removeCallbacksAndMessages(null)
        epoch++
        release()
        snapshot = PleosVehicleSnapshot()
        publish(snapshot)
    }

    override fun close() = onMain {
        if (!closed) { stop(); closed = true }
    }

    private enum class Field { ODOMETER, TRIP, SOC, PORT }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }
    private fun current(expected: Long) = active && !closed && epoch == expected
    /** SDK callbacks arrive on binder or coroutine threads; everything continues on main, within the epoch. */
    private fun later(expected: Long, action: () -> Unit) {
        main.post { if (current(expected)) action() }
    }

    private fun connect() {
        if (!active || closed) return
        release()
        val expected = ++epoch
        snapshot = PleosVehicleSnapshot()
        publish(snapshot)
        pending.addAll(Field.entries)
        val sdk = try {
            Vehicle(context).also { it.initialize() }
        } catch (e: RuntimeException) {
            Field.entries.forEach { fail(expected, it, e) }
            return
        }
        vehicle = sdk
        // The SDK queues calls while it binds and never answers them once the bind gives up, so unanswered
        // capability queries are failed here instead of waiting forever.
        main.postDelayed({ if (current(expected)) pending.toList().forEach { fail(expected, it, null) } }, ANSWER_TIMEOUT_MS)
        checkOdometer(sdk.getOdometer(), expected)
        checkTrip(sdk.getOdometer(), expected)
        checkSoc(sdk.getEvBattery(), expected)
        checkPort(sdk.getEvBattery(), expected)
    }

    private fun checkOdometer(odometer: Odometer, expected: Long) = guard(expected, Field.ODOMETER) {
        odometer.checkCurrentDistanceDrivenCapabilities(
            onComplete = { config -> later(expected) { if (answered(Field.ODOMETER, config)) readOdometer(odometer, expected) } },
            onFailed = { e -> later(expected) { fail(expected, Field.ODOMETER, e) } },
        )
    }

    private fun readOdometer(odometer: Odometer, expected: Long) {
        val listener = object : DistanceDrivenListener {   // kilometres for this registration
            override fun onDistanceDrivenUpdated(value: Float?) {
                later(expected) { update { copy(odometerKm = value?.takeIf { it.isFinite() && it >= 0f }, odometerSupported = true) } }
            }
            override fun onFailed(e: Exception) { later(expected) { fail(expected, Field.ODOMETER, e) } }
        }
        odometerListener = listener
        update { copy(odometerSupported = true) }
        guard(expected, Field.ODOMETER) {
            odometer.getCurrentDistanceDriven(onComplete = listener::onDistanceDrivenUpdated, onFailed = listener::onFailed)
            odometer.registerDistanceDriven(listener)
        }
    }

    private fun checkTrip(odometer: Odometer, expected: Long) = guard(expected, Field.TRIP) {
        odometer.checkTripCurrentDrivingDistanceCapabilities(
            onComplete = { config -> later(expected) { if (answered(Field.TRIP, config)) readTrip(odometer, expected) } },
            onFailed = { e -> later(expected) { fail(expected, Field.TRIP, e) } },
        )
    }

    private fun readTrip(odometer: Odometer, expected: Long) {
        // Metres: the SDK unwraps TripStatistics.current for the "current trip" registration.
        val listener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) {
                later(expected) { update { copy(tripMeters = value?.takeIf { it.isFinite() && it >= 0f }) } }
            }
            override fun onFailed(e: Exception) { later(expected) { fail(expected, Field.TRIP, e) } }
        }
        tripListener = listener
        guard(expected, Field.TRIP) {
            odometer.getTripCurrentDrivingDistance(onComplete = listener::onDistanceDrivenUpdated, onFailed = listener::onFailed)
            odometer.registerTripCurrentDrivingDistance(listener)
        }
    }

    private fun checkSoc(battery: EvBattery, expected: Long) = guard(expected, Field.SOC) {
        battery.checkEvBatteryStateOfChargeCapabilities(
            onComplete = { config -> later(expected) { if (answered(Field.SOC, config)) readSoc(battery, expected) } },
            onFailed = { e -> later(expected) { fail(expected, Field.SOC, e) } },
        )
    }

    private fun readSoc(battery: EvBattery, expected: Long) {
        val listener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                later(expected) { update { copy(socPercent = value?.takeIf { it in 0..100 }) } }
            }
            override fun onFailed(e: Exception) { later(expected) { fail(expected, Field.SOC, e) } }
        }
        socListener = listener
        guard(expected, Field.SOC) {
            battery.getEvBatteryStateOfCharge(onComplete = listener::onEvBatteryStateOfChargeUpdated, onFailed = listener::onFailed)
            battery.registerEvBatteryStateOfCharge(listener)
        }
    }

    private fun checkPort(battery: EvBattery, expected: Long) = guard(expected, Field.PORT) {
        battery.checkEvChargePortOpenCapabilities(
            onComplete = { config -> later(expected) { if (answered(Field.PORT, config)) readPort(battery, expected) } },
            onFailed = { e -> later(expected) { fail(expected, Field.PORT, e) } },
        )
    }

    // The SDK deprecates its charge-port reads in favour of the AAOS EV_CHARGE_PORT_OPEN property, which the
    // session's vehicle source owns for the parked bonus. This copy is display context only and stays read-only.
    @Suppress("DEPRECATION")
    private fun readPort(battery: EvBattery, expected: Long) {
        val listener = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(isOpened: Boolean?) {
                later(expected) { update { copy(chargePortOpen = isOpened) } }
            }
            override fun onFailed(e: Exception) { later(expected) { fail(expected, Field.PORT, e) } }
        }
        portListener = listener
        guard(expected, Field.PORT) {
            battery.isEvChargePortOpen(onComplete = listener::onEvChargePortOpenUpdated, onFailed = listener::onFailed)
            battery.registerEvChargePortOpen(listener)
        }
    }

    /** Runs one SDK call on main; a synchronous throw (missing permission, dead binder) fails the field closed. */
    private fun guard(expected: Long, field: Field, call: () -> Unit) {
        if (!current(expected)) return
        try { call() } catch (e: RuntimeException) { fail(expected, field, e) }
    }

    /**
     * The service answered a capability query. READ or READ_WRITE lets the field be read; anything else closes
     * it for this session. A duplicate or late answer (already answered, failed or timed out) is ignored.
     */
    private fun answered(field: Field, config: CapabilityConfig): Boolean {
        if (!pending.remove(field)) return false
        val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
        update { (if (readable) this else cleared(field)).copy(connected = true) }
        if (!readable) log("$field accessType=${config.accessType}: not readable, field stays closed")
        return readable
    }

    /** Fail closed for one field: drop its listener, null its value (odometer also unsupported), retry once. */
    private fun fail(expected: Long, field: Field, cause: Exception?) {
        if (!current(expected)) return
        pending.remove(field)
        unregister(field)
        update { cleared(field) }
        log("$field failed: " + (cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "no answer within $ANSWER_TIMEOUT_MS ms"))
        scheduleRetry(expected)
    }

    private fun scheduleRetry(expected: Long) {
        if (retried || !current(expected)) return
        retried = true
        main.postDelayed({ if (current(expected)) connect() }, RETRY_DELAY_MS)
    }

    private fun PleosVehicleSnapshot.cleared(field: Field) = when (field) {
        Field.ODOMETER -> copy(odometerKm = null, odometerSupported = false)
        Field.TRIP -> copy(tripMeters = null)
        Field.SOC -> copy(socPercent = null)
        Field.PORT -> copy(chargePortOpen = null)
    }

    private fun update(transform: PleosVehicleSnapshot.() -> PleosVehicleSnapshot) {
        snapshot = snapshot.transform()
        publish(snapshot)
    }

    @Suppress("DEPRECATION")
    private fun unregister(field: Field) {
        val sdk = vehicle
        if (sdk != null) runCatching {
            when (field) {
                Field.ODOMETER -> odometerListener?.let { sdk.getOdometer().unregisterDistanceDriven(it) }
                Field.TRIP -> tripListener?.let { sdk.getOdometer().unregisterTripCurrentDrivingDistance(it) }
                Field.SOC -> socListener?.let { sdk.getEvBattery().unregisterEvBatteryStateOfCharge(it) }
                Field.PORT -> portListener?.let { sdk.getEvBattery().unregisterEvChargePortOpen(it) }
            }
        }
        when (field) {
            Field.ODOMETER -> odometerListener = null
            Field.TRIP -> tripListener = null
            Field.SOC -> socListener = null
            Field.PORT -> portListener = null
        }
    }

    private fun release() {
        Field.entries.forEach(::unregister)
        pending.clear()
        vehicle?.let { sdk -> runCatching { sdk.release() } }
        vehicle = null
    }

    private fun publish(next: PleosVehicleSnapshot) {
        if (lastPublished == next) return
        lastPublished = next
        log("odometerKm=${next.odometerKm} tripMeters=${next.tripMeters} soc=${next.socPercent} chargePortOpen=${next.chargePortOpen} odometerSupported=${next.odometerSupported} connected=${next.connected}")
        onSnapshot(next)
    }

    /** Values only, never identifiers; debug builds only. */
    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "CashDrivePleos"
        private const val ANSWER_TIMEOUT_MS = 20_000L
        private const val RETRY_DELAY_MS = 30_000L
    }
}
