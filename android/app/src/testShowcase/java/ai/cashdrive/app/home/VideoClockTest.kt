package ai.cashdrive.app.home

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ticket clock of a video creative (plan D2): AdPlayer ticks with what the picture played, a file
 * that ends early still ends the ticket, and a video that fails or stalls hands the ticket back to the
 * wall clock so a ticket can never hang (Review Focus 2).
 */
class VideoClockTest {
    private fun clock(startMs: Long = 0, nowMs: Long = 0) = VideoClock(15_000).apply { restart(startMs, nowMs) }

    @Test fun advanceHandsOutWhatThePicturePlayed() {
        val clock = clock()
        assertEquals(1_000, clock.advance(1_000, 1_000))
        assertEquals(600, clock.advance(1_600, 1_600))
        assertEquals(0, clock.advance(1_600, 1_700))
        assertEquals(1_600, clock.positionMs)
    }

    @Test fun aPositionBehindTheMarkYieldsNothingAndNothingIsCountedTwice() {
        val clock = clock()
        clock.advance(2_000, 2_000)
        assertEquals(0, clock.advance(1_500, 2_100))
        // Playing 1.5..2.0 s again after the jump back counts nothing; only new ground does.
        assertEquals(0, clock.advance(2_000, 2_200))
        assertEquals(100, clock.advance(2_100, 2_300))
        assertEquals(2_100, clock.positionMs)
    }

    @Test fun resumingCountsFromTheSavedPositionWhileTheSeekIsInFlight() {
        val clock = clock(startMs = 7_300)
        // The player still reports the start of the file until the seek lands.
        assertEquals(0, clock.advance(0, 50))
        assertEquals(100, clock.advance(7_400, 150))
        assertEquals(7_400, clock.positionMs)
    }

    @Test fun completionHandsOutTheRestOnce() {
        val clock = clock()
        clock.advance(14_200, 14_200)
        assertEquals(800, clock.complete())
        assertEquals(0, clock.complete())
        assertEquals(0, clock.advance(15_000, 15_100))
        assertEquals(15_000, clock.positionMs)
    }

    @Test fun aShorterFileStillEndsTheTicket() {
        val clock = clock()
        clock.advance(12_000, 12_000)
        assertEquals(3_000, clock.complete())
    }

    @Test fun aLongerFileStopsAtTheEndOfTheTicket() {
        val clock = clock()
        clock.advance(14_000, 14_000)
        assertEquals(1_000, clock.advance(20_000, 14_100))
        assertEquals(0, clock.complete())
    }

    @Test fun theStartIsClampedIntoTheTicket() {
        assertEquals(0, clock(startMs = -5).positionMs)
        assertEquals(15_000, clock(startMs = 99_000).positionMs)
    }

    @Test fun restartMovesTheMarkBothWays() {
        val clock = clock()
        clock.advance(9_000, 9_000)
        clock.restart(4_000, 9_100)
        assertEquals(500, clock.advance(4_500, 9_600))
        clock.complete()
        // A finished clock starts over on restart (a new ticket of the same creative).
        clock.restart(0, 20_000)
        assertEquals(1_000, clock.advance(1_000, 21_000))
    }

    @Test fun aLoadThatNeverPlaysStalls() {
        // prepare() never completes, or the decoder hangs before the first frame.
        val clock = clock(nowMs = 1_000)
        assertFalse(clock.stalled(1_000 + VideoClock.STALL_MS - 1))
        assertTrue(clock.stalled(1_000 + VideoClock.STALL_MS))
    }

    @Test fun progressKeepsTheStallTimerFresh() {
        val clock = clock()
        clock.advance(2_000, 2_000)
        assertFalse(clock.stalled(2_000 + VideoClock.STALL_MS - 1))
        clock.advance(2_000, 4_000)
        assertTrue(clock.stalled(2_000 + VideoClock.STALL_MS))
    }

    @Test fun aFailedVideoHandsTheTicketToTheWallClock() {
        // Missing asset, broken file or a player error: the clock goes inactive and the host ticks the
        // ticket on the wall clock from there (HomeActivity: videoActive false), so the ticket still ends.
        val clock = clock()
        assertTrue(clock.active)
        clock.fail()
        assertFalse(clock.active)
        assertEquals(0, clock.advance(5_000, 5_000))
        assertEquals(0, clock.complete())
        assertFalse(clock.stalled(99_000))
    }

    @Test fun aFinishedClockNeverStalls() {
        val clock = clock()
        clock.advance(15_000, 15_000)
        clock.complete()
        assertFalse(clock.stalled(15_000 + 10 * VideoClock.STALL_MS))
    }
}
