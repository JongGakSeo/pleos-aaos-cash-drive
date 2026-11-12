package ai.cashdrive.app.home

import ai.cashdrive.core.playback.TripSummary
import java.time.LocalTime

/**
 * The scene request's figures besides the motion (design §4, §13-7, §13-11), pure so they run in JVM tests.
 * The host sends them with every request; the renderer only draws them.
 * - `minuteOfDay`: the device's minute of the day (the day theme turns to dusk from 16:30 to 18:30).
 * - `metersToNext`: the session's metres to the next ticket, always within one block (0..1000), so the
 *   milestone passes the horse when the meter issues the ticket.
 * - `milestone`: false once today's tickets are all issued. The session then reports 0 m; drawn as is, the
 *   sign would pass the horse every kilometre with nothing to earn, so the request pins a whole block and
 *   hides the sign.
 */
object SceneFields {
    fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    /** True while a ticket can still be earned today (and before any state, when nothing is known yet). */
    fun milestone(trip: TripSummary?): Boolean = trip == null || !MeterMath.capped(trip.issuedToday, trip.dailyCap)

    fun metersToNext(trip: TripSummary?): Long =
        if (trip == null || !milestone(trip)) MeterMath.BLOCK_METERS
        else trip.nextTicketMeters.coerceIn(0L, MeterMath.BLOCK_METERS)
}
