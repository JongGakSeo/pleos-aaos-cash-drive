package ai.cashdrive.core

import ai.cashdrive.core.playback.*
import org.junit.Test
import kotlin.test.*

class VehicleSignalTest {
    @Test fun parkWaitingDrivingAndNoVideoAreIndependent() {
        val r = VehicleSignalReducer(); r.reset(1, true, true)
        r.onUxRestrictions(1, false, 0); r.onSpeed(1, 10, 0f)
        assertEquals(VehicleState.UNKNOWN, r.current().vehicleState)
        assertTrue(r.onGear(1, 10, ParkSignal.PARK).playbackAllowed)
        assertEquals(VehicleState.STOPPED_WAITING, r.onGear(1, 11, ParkSignal.NOT_PARK).vehicleState)
        // A waiting stop (D, 0 km/h) never plays, even without the NO_VIDEO bit.
        assertFalse(r.current().playbackAllowed)
        assertFalse(r.onUxRestrictions(1, true, 64).playbackAllowed)
        assertEquals(VehicleState.STOPPED_PARKED, r.onGear(1, 12, ParkSignal.PARK).vehicleState)
        assertTrue(r.current().playbackAllowed)
        assertFalse(r.onUxRestrictions(1, true, 16).playbackAllowed)
        assertTrue(r.onUxRestrictions(1, true, 64).playbackAllowed)
        val driving = r.onSpeed(1, 12, 10f)
        assertEquals(VehicleState.DRIVING, driving.vehicleState); assertEquals(36.0, driving.speedKph)
        assertFalse(driving.playbackAllowed)
    }
    @Test fun chargePortFollowsTimestampsAndEpoch() {
        val r = VehicleSignalReducer(); r.reset(1, connected = true, speedPermission = true)
        r.onConnected(1, true)
        assertNull(r.current().chargePortConnected)
        assertEquals(true, r.onChargePort(1, 200, true).chargePortConnected)
        assertEquals(true, r.onChargePort(1, 100, false).chargePortConnected)   // older sample ignored
        assertEquals(null, r.onChargePort(1, 300, null).chargePortConnected)
        assertEquals(null, r.onChargePort(2, 400, true).chargePortConnected)    // wrong epoch ignored
        assertEquals(false, r.onChargePort(1, 400, false).chargePortConnected)
        assertNull(r.reset(2, true, true).chargePortConnected)                  // never survives a reset
    }
    @Test fun reverseMotionUsesSpeedMagnitudeAndNeverCountsAsStopped() {
        val r = VehicleSignalReducer(); r.reset(1, true, true)
        assertEquals(18.0, r.onSpeed(1, 1, -5f).speedKph)
        assertEquals(VehicleState.DRIVING, r.current().vehicleState)
    }
    @Test fun oldGenerationAndOutOfOrderSamplesCannotReopenGate() {
        val r = VehicleSignalReducer(); r.reset(1, true, true)
        r.onUxRestrictions(1, false, 0); r.onGear(1, 10, ParkSignal.PARK); r.onSpeed(1, 10, 0f)
        r.onSpeed(1, 20, null); assertFalse(r.onSpeed(1, 19, 0f).playbackAllowed)
        r.onGear(1, 30, ParkSignal.UNKNOWN); r.onGear(1, 29, ParkSignal.PARK)
        assertEquals(VehicleState.UNKNOWN, r.current().vehicleState)
        r.reset(2); r.onSpeed(1, 100, 0f); r.onUxRestrictions(1, false, 0)
        assertFalse(r.current().playbackAllowed)
        r.reset(3, true, true); r.onSpeed(3, 1, 0f); r.onGear(3, 1, ParkSignal.PARK)
        assertFalse(r.current().playbackAllowed)
        assertTrue(r.onUxRestrictions(3, false, 0).playbackAllowed)
    }
    @Test fun deniedPermissionAndUnavailableSignalsStayUnknown() {
        val r = VehicleSignalReducer(); r.reset(1, true, false)
        r.onUxRestrictions(1, false, 0); r.onGear(1, 1, ParkSignal.PARK)
        assertEquals(VehicleState.UNKNOWN, r.onSpeed(1, 1, 0f).vehicleState)
        r.reset(2, true, true); r.onGear(2, 1, ParkSignal.PARK); r.onUxRestrictions(2, false, 0)
        assertFalse(r.onSpeed(2, 1, Float.NaN).playbackAllowed)
        assertFalse(r.onSpeed(2, 2, Float.POSITIVE_INFINITY).playbackAllowed)
    }
}
