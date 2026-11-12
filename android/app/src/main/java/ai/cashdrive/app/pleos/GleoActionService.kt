package ai.cashdrive.app.pleos

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.session.SqlitePlaybackAccountStore
import ai.cashdrive.core.playback.PlaybackAccountRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gleo App Schema actions (assets/schema.json). Gleo delivers an action as an Intent whose action is
 * the function name, with the JSON parameters in the "ACTION_PARAM" extra, and expects one broadcast
 * reply on APP_ACTION_RESPONSE within 7 seconds. This service only reads the LOCAL_MOCK ledger and
 * brings the home to the front: it never writes the ledger, never reads vehicle signals and never
 * credits anything. Declared as a started service for the `am startservice` evidence path.
 */
class GleoActionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "CashDriveGleo") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null || action !in FUNCTIONS) {
            Log.w(TAG, "Ignoring action $action")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val param = intent.getStringExtra(PARAM_EXTRA) ?: "{}"
        runCatching { JSONObject(param) }.onFailure { Log.w(TAG, "$PARAM_EXTRA is not JSON (${param.length} chars), ignored") }
        val replied = AtomicBoolean(false)
        val reply = { response: Response ->
            if (replied.compareAndSet(false, true)) { send(response); stopSelf(startId) }
        }
        // Gleo cancels the action at 7 s: answer "fail" first if the ledger read ever hangs.
        val deadline = Runnable { reply(Response(action, STATUS_FAIL, "KiloMoa could not answer in time.")) }
        main.postDelayed(deadline, REPLY_DEADLINE_MS)
        worker.execute {
            val response = try { handle(action) } catch (e: Exception) {
                Log.w(TAG, "$action failed", e)
                Response(action, STATUS_FAIL, "KiloMoa could not complete the request.")
            }
            main.post { main.removeCallbacks(deadline); reply(response) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun handle(action: String): Response = when (action) {
        CHECK_REWARD_BALANCE -> checkRewardBalance()
        else -> openCashDrive()
    }

    private fun checkRewardBalance(): Response {
        if (!BuildConfig.LOCAL_MOCK) return Response(CHECK_REWARD_BALANCE, STATUS_FAIL,
            "This build has no reward account to report.", bilingual("이 빌드에는 보상 계정이 없어요", "This build has no reward account to report."))
        val record = SqlitePlaybackAccountStore(this).use { it.read(ACCOUNT) }
        val balance = RewardBalance.of(record, dayKey())
        return Response(CHECK_REWARD_BALANCE, STATUS_SUCCESS, balance.english, JSONArray().put(balance.toJson()))
    }

    /** Explicit start by component name: the home class exists only in debug and showcase builds. */
    private fun openCashDrive(): Response {
        val home = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(BuildConfig.APPLICATION_ID, HOME_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(home)
            Response(OPEN_KILOMOA, STATUS_SUCCESS, "KiloMoa is opening on the screen.",
                bilingual("킬로모아를 열어 드릴게요", "KiloMoa is opening on the screen."))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No home activity in this build", e)
            Response(OPEN_KILOMOA, STATUS_FAIL, "The KiloMoa home screen is not available in this build.",
                bilingual("이 빌드에는 킬로모아 홈 화면이 없어요", "The KiloMoa home screen is not available in this build."))
        }
    }

    /**
     * Reply contract: functionName (schema name), status success|asking|fail, message (one English
     * sentence), optional data (a JSON array serialized as a string) and mode voice|beep|silent.
     */
    private fun send(response: Response) {
        val json = JSONObject().put("functionName", response.functionName).put("status", response.status)
            .put("message", response.message).put("mode", MODE_VOICE)
        response.data?.let { json.put("data", it.toString()) }
        val reply = Intent(RESPONSE_ACTION).putExtra("packageName", packageName).putExtra("response", json.toString())
        // The reply carries only the mock balance, but it is still sent as explicit broadcasts to the
        // system apps that receive APP_ACTION_RESPONSE (Gleo), never as an implicit broadcast every app can read.
        val targets = replyTargets()
        targets.forEach { target -> sendBroadcast(Intent(reply).setPackage(target)) }
        Log.i(TAG, "$RESPONSE_ACTION to ${targets.size} system receiver(s): $json")
    }

    /** System (platform-signed or preinstalled) receivers of the reply action, plus the known Gleo package. */
    private fun replyTargets(): Set<String> {
        val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        val resolved = runCatching {
            packageManager.queryBroadcastReceivers(Intent(RESPONSE_ACTION), 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.flags and system != 0 }
                .map { it.packageName }
        }.getOrDefault(emptyList())
        return (resolved + GLEO_PACKAGE).toSet()
    }

    private fun bilingual(ko: String, en: String): JSONArray =
        JSONArray().put(JSONObject().put("messageKo", ko).put("messageEn", en))

    /** Device-local calendar day, the same shape (`YYYYMMDD`) the trip meter stores. */
    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())

    private data class Response(val functionName: String, val status: String, val message: String, val data: JSONArray? = null)

    /** Spoken read model of the LOCAL_MOCK ledger: a pure function of the stored record. */
    internal data class RewardBalance(val todayMeters: Long, val tickets: Int, val credits: Int) {
        val todayKm: String get() = String.format(Locale.ROOT, "%.1f", todayMeters / 1000.0)
        val korean: String get() =
            if (todayMeters > 0) "오늘 $todayKm km를 달려 시청권 ${tickets}장, 크레딧 ${credits}이 있어요"
            else "오늘은 아직 달린 거리가 없어요. 시청권 ${tickets}장, 크레딧 ${credits}이 있어요"
        val english: String get() =
            (if (todayMeters > 0) "You have driven $todayKm km today" else "You have not driven yet today") +
                " and have ${count(tickets, "briefing ticket")} and ${count(credits, "credit")}."

        fun toJson(): JSONObject = JSONObject().put("todayKm", todayKm).put("tickets", tickets).put("credits", credits)
            .put("messageKo", korean).put("messageEn", english).put("source", "LOCAL_MOCK")

        companion object {
            /** No record (fresh install) reads as zero everywhere. Tickets are the ads not yet credited, as the home counts them. */
            fun of(record: PlaybackAccountRecord?, dayKey: String): RewardBalance {
                if (record == null) return RewardBalance(0, 0, 0)
                val credited = record.ledger.map { it.adId }.toSet()
                val meter = record.meter
                // The session host owns the day boundary: an empty stored key means it keeps none yet,
                // and a key from another day means today's distance restarted at zero.
                val today = if (meter.dayKey.isEmpty() || meter.dayKey == dayKey) meter.todayMeters else 0L
                return RewardBalance(today, record.ads.count { it.adId !in credited }, record.totalPoints)
            }

            private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"
        }
    }

    companion object {
        const val CHECK_REWARD_BALANCE = "CHECK_REWARD_BALANCE"
        const val OPEN_KILOMOA = "OPEN_KILOMOA"
        /** Gleo AI takes the reply as a broadcast; this app sends it only to system receivers (see replyTargets). */
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        /** Gleo AI package on Pleos Connect v2.1.0 (ai.umos.ambientai), the receiver seen on the emulator. */
        const val GLEO_PACKAGE = "ai.umos.ambientai"
        const val PARAM_EXTRA = "ACTION_PARAM"
        /** The mock account the home uses (HomeActivity.ACCOUNT); the home class is not on this build's class path. */
        const val ACCOUNT = "demo-home"
        const val HOME_ACTIVITY = "ai.cashdrive.app.home.HomeActivity"
        const val REPLY_DEADLINE_MS = 5_000L
        private const val STATUS_SUCCESS = "success"
        private const val STATUS_FAIL = "fail"
        private const val MODE_VOICE = "voice"
        private const val TAG = "CashDriveGleo"
        private val FUNCTIONS = setOf(CHECK_REWARD_BALANCE, OPEN_KILOMOA)
    }
}
