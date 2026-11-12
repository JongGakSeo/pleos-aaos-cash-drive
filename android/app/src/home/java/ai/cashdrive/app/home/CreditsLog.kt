package ai.cashdrive.app.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When each ticket was credited (design §13-8): the host writes the ticket id and the time of its
 * completion, so the credits page can show the time of an entry and today's total. The session's ledger
 * stays the record of credits; this log only adds the times and never decides a reward. A ticket is
 * logged once (its first time wins) and the log keeps its newest [max] entries, in credit order.
 *
 * Stored in SharedPreferences "credits", key "log" (credits.log) as a JSON array of
 * {"id": ticketId, "at": epochMs}. Main thread only.
 */
class CreditsLog internal constructor(
    private val store: Store,
    private val max: Int = MAX_ENTRIES,
    private val zone: () -> ZoneId,
) {
    /** Where the entries live: SharedPreferences on the device, memory in the JVM tests. */
    interface Store {
        fun load(): List<Pair<String, Long>>
        fun save(entries: List<Pair<String, Long>>)
    }

    constructor(context: Context) : this(PreferencesStore(context), MAX_ENTRIES, { ZoneId.systemDefault() })

    /** Logs [ticketId] credited at [epochMs]. A blank id, a negative time or a ticket already logged changes nothing. */
    fun record(ticketId: String, epochMs: Long) {
        if (ticketId.isBlank() || epochMs < 0) return
        val entries = store.load()
        if (entries.any { it.first == ticketId }) return
        store.save((entries + (ticketId to epochMs)).takeLast(max.coerceAtLeast(1)))
    }

    /** Every logged ticket with its credit time, oldest first. */
    fun entries(): List<Pair<String, Long>> = store.load()

    /** Points credited on the device-local day [dayKey] ("YYYYMMDD", the session's day key). */
    fun todayPoints(dayKey: String, pointsPerTicket: Int = POINTS_PER_TICKET): Int {
        val zone = zone()
        return store.load().count { dayKeyOf(it.second, zone) == dayKey } * pointsPerTicket
    }

    private class PreferencesStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** A missing or unreadable log reads as empty; entries without an id or a time are skipped. */
        override fun load(): List<Pair<String, Long>> {
            val text = prefs.getString(KEY, null) ?: return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { i ->
                    val entry = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = entry.optString(ID)
                    val at = entry.optLong(AT, -1)
                    if (id.isBlank() || at < 0) null else id to at
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        override fun save(entries: List<Pair<String, Long>>) {
            val array = JSONArray()
            entries.forEach { (id, at) -> array.put(JSONObject().put(ID, id).put(AT, at)) }
            prefs.edit().putString(KEY, array.toString()).apply()
        }
    }

    companion object {
        const val POINTS_PER_TICKET = 10
        /** A hundred days at the daily cap. */
        const val MAX_ENTRIES = 1_000
        private const val PREFS = "credits"
        private const val KEY = "log"
        private const val ID = "id"
        private const val AT = "at"

        /** The device-local calendar day of [epochMs] in the session's format ("YYYYMMDD"). */
        fun dayKeyOf(epochMs: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
    }
}
