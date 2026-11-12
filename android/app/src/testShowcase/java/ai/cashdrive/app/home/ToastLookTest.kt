package ai.cashdrive.app.home

import ai.cashdrive.app.R
import ai.cashdrive.core.playback.VehicleState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The toast's pure decisions (plan B2, design §8): which 24dp glyph it carries and whether it may slide
 * in and out. A toast moves only where the meter moves fully, in park; a notice that arrives while the
 * car leaves park, waits at a light or drives appears and leaves in place.
 */
class ToastLookTest {
    @Test fun callerGlyphWins() {
        assertEquals(R.drawable.ic_brief_check, ToastLook.icon(reward = true, icon = R.drawable.ic_brief_check))
        assertEquals(R.drawable.ic_route, ToastLook.icon(reward = false, icon = R.drawable.ic_route))
    }

    @Test fun rewardsDefaultToTheStub() {
        assertEquals(R.drawable.ic_stub, ToastLook.icon(reward = true, icon = null))
    }

    @Test fun noticesDefaultToTheInfoMark() {
        assertEquals(R.drawable.ic_toast_info, ToastLook.icon(reward = false, icon = null))
    }

    @Test fun movesOnlyInParkWithMotion() {
        assertTrue(ToastLook.animates(MotionLevel.of(VehicleState.STOPPED_PARKED, motionWhileDriving = true, reduceMotion = false)))
        assertTrue(ToastLook.animates(MotionLevel.of(VehicleState.STOPPED_PARKED, motionWhileDriving = false, reduceMotion = false)))
    }

    @Test fun neverMovesOutsideParkOrWithReducedMotion() {
        // Driving with the motion setting on lets the meter move at half length; a toast's text still never slides.
        assertFalse(ToastLook.animates(MotionLevel.of(VehicleState.DRIVING, motionWhileDriving = true, reduceMotion = false)))
        assertFalse(ToastLook.animates(MotionLevel.of(VehicleState.DRIVING, motionWhileDriving = false, reduceMotion = false)))
        assertFalse(ToastLook.animates(MotionLevel.of(VehicleState.STOPPED_WAITING, motionWhileDriving = true, reduceMotion = false)))
        assertFalse(ToastLook.animates(MotionLevel.of(VehicleState.UNKNOWN, motionWhileDriving = true, reduceMotion = false)))
        assertFalse(ToastLook.animates(MotionLevel.of(VehicleState.STOPPED_PARKED, motionWhileDriving = true, reduceMotion = true)))
    }
}
