package ai.cashdrive.app.home

import ai.cashdrive.core.playback.VehicleState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The toast when a briefing stops because playback is no longer allowed (review 2026-11-09): it says what
 * actually happened. Shifting out of P at 0 km/h is not a departure, and a lost vehicle state is neither.
 */
class InterruptNoticeTest {
    @Test fun drivingOffSaysItStarted() {
        assertEquals("출발해서 브리핑을 멈췄어요. 다음에 주차하면 0:05부터 이어서 봐요", InterruptNotice.text(VehicleState.DRIVING, 5_400))
    }

    @Test fun leavingParkStandingStillSaysItLeftPark() {
        assertEquals("P단에서 벗어나 브리핑을 멈췄어요. 다시 주차하면 0:05부터 이어서 봐요", InterruptNotice.text(VehicleState.STOPPED_WAITING, 5_000))
    }

    @Test fun anythingElseOnlySaysItStopped() {
        // Lost signals, restrictions in park: no claim about the gear or a departure.
        val unknown = InterruptNotice.text(VehicleState.UNKNOWN, 12_900)
        assertEquals("차량 상태를 확인하는 동안 브리핑을 멈췄어요. 주차가 확인되면 0:12부터 이어서 봐요", unknown)
        assertEquals(unknown, InterruptNotice.text(VehicleState.STOPPED_PARKED, 12_000))
        assertFalse(unknown.contains("출발"))
    }

    @Test fun positionIsWholeSecondsWithTwoDigits() {
        assertEquals("출발해서 브리핑을 멈췄어요. 다음에 주차하면 0:00부터 이어서 봐요", InterruptNotice.text(VehicleState.DRIVING, 999))
        assertEquals("출발해서 브리핑을 멈췄어요. 다음에 주차하면 0:14부터 이어서 봐요", InterruptNotice.text(VehicleState.DRIVING, 14_999))
    }
}
