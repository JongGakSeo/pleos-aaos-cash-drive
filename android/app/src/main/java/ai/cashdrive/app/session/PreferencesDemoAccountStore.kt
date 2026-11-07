package ai.cashdrive.app.session

import android.content.Context
import ai.cashdrive.core.rewards.RewardTicket
import ai.cashdrive.core.rewards.TicketStatus
import ai.cashdrive.core.rewards.WalletSnapshot
import ai.cashdrive.core.session.DemoAccountRecord
import ai.cashdrive.core.session.DemoAccountStore
import ai.cashdrive.core.session.ThemeIds
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Per-account, atomically committed LOCAL_MOCK data; contains no tokens or real reward balance. */
class PreferencesDemoAccountStore(context: Context) : DemoAccountStore {
    private val preferences = context.applicationContext.getSharedPreferences("cash_drive_session_v1", Context.MODE_PRIVATE)
    private val legacy = context.applicationContext.getSharedPreferences("cash_drive_local", Context.MODE_PRIVATE)

    override fun read(accountId: String): DemoAccountRecord? = synchronized(lock) { readLocked(accountId) }

    override fun update(accountId: String, change: (DemoAccountRecord?) -> DemoAccountRecord): DemoAccountRecord = synchronized(lock) {
        require(accountId.isNotBlank())
        val next = change(readLocked(accountId))
        require(next.wallet.accountId == accountId)
        val tickets = JSONArray().apply {
            next.wallet.tickets.forEach { ticket -> put(JSONObject().put("id", ticket.id)
                .put("points", ticket.points).put("status", ticket.status.name)) }
        }
        val json = JSONObject().put("schemaVersion", 1).put("accountId", accountId)
            .put("confirmedPoints", next.wallet.confirmedPoints).put("tickets", tickets)
            .put("themeId", ThemeIds.normalize(next.themeId)).put("tripId", next.tripId)
            .put("tripCandidatePoints", next.tripCandidatePoints)
        if (!preferences.edit().putString("account:$accountId", json.toString()).commit()) {
            throw IOException("Local demo store commit failed")
        }
        next
    }

    private fun readLocked(accountId: String): DemoAccountRecord? {
        val text = preferences.getString("account:$accountId", null) ?: return legacyRecord(accountId)
        val json = JSONObject(text)
        check(json.getInt("schemaVersion") == 1 && json.getString("accountId") == accountId)
        val rows = json.getJSONArray("tickets")
        val tickets = (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            RewardTicket(it.getString("id"), accountId, it.getInt("points"), TicketStatus.valueOf(it.getString("status")))
        } }
        return DemoAccountRecord(WalletSnapshot(accountId, json.getInt("confirmedPoints"), tickets),
            ThemeIds.normalize(json.opt("themeId") as? String), json.getString("tripId"), json.getInt("tripCandidatePoints"))
    }

    /** Import the old UI demo once; never edit its preferences or merge it with server points. */
    private fun legacyRecord(accountId: String): DemoAccountRecord? {
        if (!legacy.contains("confirmed:$accountId") && !legacy.contains("skin:$accountId")) return null
        val tickets = legacy.getString("tickets:$accountId", "").orEmpty().split(';').filter { it.isNotBlank() }.map { row ->
            val pieces = row.split(':')
            check(pieces.size == 3) { "Invalid legacy demo ticket" }
            RewardTicket(pieces[0], accountId, pieces[1].toInt(), TicketStatus.valueOf(pieces[2]))
        }
        return DemoAccountRecord(WalletSnapshot(accountId, legacy.getInt("confirmed:$accountId", 0), tickets),
            ThemeIds.normalize(legacy.all["skin:$accountId"] as? String),
            tripCandidatePoints = legacy.getInt("tripPoints:$accountId", 0))
    }

    private companion object { val lock = Any() }
}
