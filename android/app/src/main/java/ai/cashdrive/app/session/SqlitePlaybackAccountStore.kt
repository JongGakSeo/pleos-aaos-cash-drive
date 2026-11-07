package ai.cashdrive.app.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ai.cashdrive.core.playback.*
import ai.cashdrive.core.rewards.TicketStatus
import ai.cashdrive.core.session.ThemeIds
import org.json.JSONArray
import org.json.JSONObject

/** Durable per-account LOCAL_MOCK records. No credentials, vehicle signals, or real balance. */
class SqlitePlaybackAccountStore(context: Context) : PlaybackAccountStore, AutoCloseable {
    private val legacy = PreferencesDemoAccountStore(context)
    private val database = object : SQLiteOpenHelper(context.applicationContext, "cash_drive_playback_v2.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE accounts (account_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported playback schema")
    }

    override fun read(accountId: String): PlaybackAccountRecord? = synchronized(lock) {
        require(accountId.isNotBlank())
        read(database.readableDatabase, accountId) ?: migrate(accountId)
    }

    override fun update(accountId: String, change: (PlaybackAccountRecord?) -> PlaybackAccountRecord): PlaybackAccountRecord = synchronized(lock) {
        require(accountId.isNotBlank())
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val next = change(read(db, accountId) ?: migrate(accountId)).validate()
            require(next.accountId == accountId)
            db.insertWithOnConflict("accounts", null, ContentValues().apply {
                put("account_id", accountId); put("payload", encode(next))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.setTransactionSuccessful()
            next
        } finally { db.endTransaction() }
    }

    private fun read(db: SQLiteDatabase, accountId: String): PlaybackAccountRecord? =
        db.query("accounts", arrayOf("payload"), "account_id = ?", arrayOf(accountId), null, null, null).use {
            if (it.moveToFirst()) decode(it.getString(0), accountId) else null
        }

    /** One-way import, persisted in the same v2 transaction; old files are never changed.
     * Old confirmed totals remain opening balance. Unconfirmed/unfinished ads replay from 0;
     * old manual confirmation never becomes an unverified automatic credit.
     */
    private fun migrate(accountId: String): PlaybackAccountRecord? {
        val old = legacy.read(accountId) ?: return null
        return PlaybackAccountRecord(accountId,
            ads = old.wallet.tickets.map { AdPlayback(it.id, if (it.status == TicketStatus.CREDITED) 15_000 else 0, 15_000) },
            ledger = old.wallet.tickets.filter { it.status == TicketStatus.CREDITED }
                .map { MockCredit(it.id, 0, CreditReason.IMPORTED_V1_CREDIT) },
            openingPoints = old.wallet.confirmedPoints, tripCandidatePoints = old.tripCandidatePoints,
            tripId = old.tripId, themeId = ThemeIds.normalize(old.themeId)).validate()
    }

    private fun encode(record: PlaybackAccountRecord): String = JSONObject().apply {
        put("schemaVersion", 2); put("accountId", record.accountId)
        put("openingPoints", record.openingPoints); put("tripCandidatePoints", record.tripCandidatePoints)
        put("tripId", record.tripId); put("themeId", ThemeIds.normalize(record.themeId))
        put("ads", JSONArray().apply { record.ads.forEach { put(JSONObject().put("adId", it.adId)
            .put("positionMs", it.positionMs).put("durationMs", it.durationMs)) } })
        put("ledger", JSONArray().apply { record.ledger.forEach { put(JSONObject().put("adId", it.adId)
            .put("points", it.points).put("reason", it.reason.name)) } })
    }.toString()

    private fun decode(text: String, accountId: String): PlaybackAccountRecord {
        val json = JSONObject(text)
        check(json.getInt("schemaVersion") == 2 && json.getString("accountId") == accountId)
        val ads = json.getJSONArray("ads")
        val ledger = json.getJSONArray("ledger")
        return PlaybackAccountRecord(accountId,
            ads = (0 until ads.length()).map { ads.getJSONObject(it).let { ad ->
                AdPlayback(ad.getString("adId"), ad.getLong("positionMs"), ad.getLong("durationMs")) } },
            ledger = (0 until ledger.length()).map { ledger.getJSONObject(it).let { entry ->
                MockCredit(entry.getString("adId"), entry.getInt("points"), CreditReason.valueOf(entry.getString("reason"))) } },
            openingPoints = json.getInt("openingPoints"), tripCandidatePoints = json.getInt("tripCandidatePoints"),
            tripId = json.getString("tripId"), themeId = ThemeIds.normalize(json.opt("themeId") as? String)).validate()
    }

    override fun close() = synchronized(lock) { database.close() }
    private companion object { val lock = Any() }
}
