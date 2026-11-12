package ai.cashdrive.core.playback

import kotlin.math.abs

enum class ParkSignal { PARK, NOT_PARK, UNKNOWN }

/** Public AAOS speed/gear evidence, isolated by connection generation and VHAL timestamps. */
class VehicleSignalReducer {
    private var generation = 0L
    private var connected = false
    private var permission = false
    private var speed: Double? = null
    private var park = ParkSignal.UNKNOWN
    private var activeRestrictions: Int? = null
    private var requiresDo: Boolean? = null
    private var chargePort: Boolean? = null
    private var speedTimestamp = -1L
    private var gearTimestamp = -1L
    private var chargeTimestamp = -1L

    @Synchronized fun reset(epoch: Long, connected: Boolean = false, speedPermission: Boolean = false): VehicleStatus {
        require(epoch > generation)
        generation = epoch; this.connected = connected; permission = speedPermission
        speed = null; park = ParkSignal.UNKNOWN; activeRestrictions = null; requiresDo = null; chargePort = null
        speedTimestamp = -1; gearTimestamp = -1; chargeTimestamp = -1
        return current()
    }
    @Synchronized fun onConnected(epoch: Long, speedPermission: Boolean): VehicleStatus {
        if (epoch == generation) { connected = true; permission = speedPermission }
        return current()
    }
    @Synchronized fun onSpeed(epoch: Long, timestampNanos: Long, metersPerSecond: Float?): VehicleStatus {
        if (epoch == generation && connected && permission && timestampNanos >= speedTimestamp) {
            speedTimestamp = timestampNanos
            speed = metersPerSecond?.takeIf { it.isFinite() }?.let { abs(it.toDouble()) * 3.6 }
        }
        return current()
    }
    @Synchronized fun onGear(epoch: Long, timestampNanos: Long, signal: ParkSignal): VehicleStatus {
        if (epoch == generation && connected && timestampNanos >= gearTimestamp) {
            gearTimestamp = timestampNanos; park = signal
        }
        return current()
    }
    @Synchronized fun onUxRestrictions(epoch: Long, requiresDo: Boolean?, active: Int?): VehicleStatus {
        if (epoch == generation && connected) {
            this.requiresDo = requiresDo; activeRestrictions = active?.takeIf { it >= 0 }
        }
        return current()
    }
    /** EV charge port evidence; informational, never part of the playback gate. */
    @Synchronized fun onChargePort(epoch: Long, timestampNanos: Long, connected: Boolean?): VehicleStatus {
        if (epoch == generation && this.connected && timestampNanos >= chargeTimestamp) {
            chargeTimestamp = timestampNanos; chargePort = connected
        }
        return current()
    }
    @Synchronized fun current(): VehicleStatus {
        val state = when {
            !connected || !permission || speed == null -> VehicleState.UNKNOWN
            speed!! > 0 -> VehicleState.DRIVING
            park == ParkSignal.PARK -> VehicleState.STOPPED_PARKED
            park == ParkSignal.NOT_PARK -> VehicleState.STOPPED_WAITING
            else -> VehicleState.UNKNOWN
        }
        return VehicleStatus(state, speed, connected, permission, activeRestrictions, requiresDo, chargePort)
    }
}
