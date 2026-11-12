package ai.cashdrive.app.home

import ai.cashdrive.app.R
import ai.cashdrive.core.playback.TripSummary
import org.junit.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the host puts in the scene request besides the motion (review 2026-11-09, design §4, §13-7, §13-11):
 * the minute of the day for the dusk palette, the metres to the next ticket for the milestone and whether
 * there is a next ticket at all; and which still stands behind the engine, the lock and the gate.
 */
class SceneFieldsTest {
    private fun trip(next: Long, issued: Int, cap: Int = 10) =
        TripSummary(tripMeters = 0, todayMeters = 0, nextTicketMeters = next, issuedToday = issued, dailyCap = cap,
            revealPending = false, charging = null)

    @Test fun minuteOfDayCountsFromMidnight() {
        assertEquals(0, SceneFields.minuteOfDay(LocalTime.MIDNIGHT))
        assertEquals(990, SceneFields.minuteOfDay(LocalTime.of(16, 30)))
        assertEquals(1000, SceneFields.minuteOfDay(LocalTime.of(16, 40, 59)))
        assertEquals(1439, SceneFields.minuteOfDay(LocalTime.of(23, 59, 59)))
    }

    @Test fun metersToNextIsTheSessionsDistanceWithinOneBlock() {
        assertEquals(100L, SceneFields.metersToNext(trip(next = 100, issued = 3)))
        assertEquals(1_000L, SceneFields.metersToNext(trip(next = 1_000, issued = 0)))
        // Godot rejects anything outside 0..1000 and falls back to STATIC: never send it.
        assertEquals(1_000L, SceneFields.metersToNext(trip(next = 4_000, issued = 0)))
        assertEquals(0L, SceneFields.metersToNext(trip(next = -5, issued = 0)))
    }

    @Test fun beforeAnyStateTheMilestoneIsAWholeBlockAway() {
        assertEquals(1_000L, SceneFields.metersToNext(null))
        assertTrue(SceneFields.milestone(null))
    }

    @Test fun atTheCapTheSignHidesAndTheDistanceIsPinned() {
        // The session reports 0 m at the cap; drawn as is, the sign would pass the horse every kilometre with
        // no ticket. The request pins a whole block and hides the sign instead.
        assertTrue(SceneFields.milestone(trip(next = 400, issued = 9)))
        assertFalse(SceneFields.milestone(trip(next = 0, issued = 10)))
        assertFalse(SceneFields.milestone(trip(next = 0, issued = 11)))
        assertEquals(1_000L, SceneFields.metersToNext(trip(next = 0, issued = 10)))
    }

    @Test fun theStillBehindTheEngineFollowsTheSceneAreaNotTheWindow() {
        // The wide panel's scene is a column beside the meter (1010x1122), the narrow panel's a band (840x702).
        assertEquals(R.drawable.scene_horse_day_wide, Stills.scene("horse", night = false, width = 1010, height = 1122))
        assertEquals(R.drawable.scene_horse_night_wide, Stills.scene("horse", night = true, width = 1010, height = 1122))
        assertEquals(R.drawable.scene_horse_day_narrow, Stills.scene("horse", night = false, width = 840, height = 702))
        assertEquals(R.drawable.scene_horse_night_narrow, Stills.scene("horse", night = true, width = 840, height = 702))
        // The same band rule as ThemeStage.layout_for: at least 1.08 times as wide as tall.
        assertEquals(R.drawable.scene_horse_day_wide, Stills.scene("horse", night = false, width = 1079, height = 1000))
        assertEquals(R.drawable.scene_horse_day_narrow, Stills.scene("horse", night = false, width = 1080, height = 1000))
        assertEquals(R.drawable.scene_horse_day_wide, Stills.scene("horse", night = false, width = 0, height = 0))
    }

    @Test fun theLockAndTheGateUseThePanoramaOnALandscapeWindow() {
        assertEquals(R.drawable.scene_horse_day_panorama, Stills.backdrop("horse", night = false, landscape = true))
        assertEquals(R.drawable.scene_horse_night_panorama, Stills.backdrop("horse", night = true, landscape = true))
        // A portrait window stands the column still on its bottom edge: the horse keeps the scene's size.
        assertEquals(R.drawable.scene_horse_day_wide, Stills.backdrop("horse", night = false, landscape = false))
        assertEquals(R.drawable.scene_horse_night_wide, Stills.backdrop("horse", night = true, landscape = false))
        // Each theme has its own stills; an unknown theme shows the horse, like the session's fallback.
        assertEquals(R.drawable.scene_mine_day_narrow, Stills.scene("mine", night = false, width = 840, height = 702))
        assertEquals(R.drawable.scene_mining_night_wide, Stills.scene("mining", night = true, width = 1010, height = 1122))
        assertEquals(R.drawable.scene_mine_night_panorama, Stills.backdrop("mine", night = true, landscape = true))
        assertEquals(R.drawable.scene_mining_day_thumb, Stills.thumb("mining", night = false))
        assertEquals(R.drawable.scene_horse_day_wide, Stills.scene("gpu", night = false, width = 1010, height = 1122))
    }
}
