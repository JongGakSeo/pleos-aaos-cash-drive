package ai.cashdrive.core

import ai.cashdrive.core.playback.*
import org.junit.Test
import kotlin.test.*

class OdometerCrossCheckTest {
    private val check = OdometerCrossCheck()

    @Test fun firstSampleOnlySetsBaseline() {
        val (state, accepted) = check.accept(OdometerState(), 1.0f, 0)
        assertEquals(0, accepted); assertEquals(OdometerState(lastKm = 1.0f, heldDeltas = 0), state)
    }
    @Test fun acceptsDeltaConfirmedByMovement() {
        val (state, accepted) = check.accept(OdometerState(lastKm = 1.0f), 2.0f, 980)   // 1.0 -> 2.0 km while the meter saw 980 m
        assertEquals(1_000, accepted); assertEquals(OdometerState(lastKm = 2.0f, heldDeltas = 0), state)
        assertEquals(5_000, check.accept(OdometerState(lastKm = 0f), 5.0f, 4_900).second)   // the cap itself is still plausible
    }
    @Test fun decreasingReadingRebasesWithoutAccepting() {
        val (state, accepted) = check.accept(OdometerState(lastKm = 2.0f), 1.5f, 400)
        assertEquals(0, accepted); assertEquals(OdometerState(lastKm = 1.5f, heldDeltas = 0), state)
    }
    @Test fun jumpBeyondFiveKilometresIsHeld() {
        val (state, accepted) = check.accept(OdometerState(lastKm = 2.0f), 9.0f, 6_900)   // 7,000 m in one reading
        assertEquals(0, accepted); assertEquals(OdometerState(lastKm = 9.0f, heldDeltas = 1), state)
    }
    @Test fun deltaWithoutMovementIsHeld() {
        val (state, accepted) = check.accept(OdometerState(lastKm = 2.0f, heldDeltas = 1), 3.0f, 0)
        assertEquals(0, accepted); assertEquals(OdometerState(lastKm = 3.0f, heldDeltas = 2), state)
    }
    @Test fun unchangedOrInvalidReadingsChangeNothing() {
        val base = OdometerState(lastKm = 2.0f, heldDeltas = 1)
        val same = check.accept(base, 2.0f, 500)                                   // same reading: nothing to confirm
        assertEquals(base, same.first); assertEquals(0, same.second)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1.0f).forEach { reading ->
            val (state, accepted) = check.accept(base, reading, 500)               // garbage never moves the baseline
            assertEquals(base, state, "$reading"); assertEquals(0, accepted, "$reading")
        }
        assertFailsWith<IllegalArgumentException> { check.accept(base, 3.0f, -1) }
        assertFailsWith<IllegalArgumentException> { OdometerCrossCheck(maxDeltaMeters = 0) }
    }
}
