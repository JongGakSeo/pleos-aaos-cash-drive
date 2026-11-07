package ai.cashdrive.app.network

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.cashdrive.app.BuildConfig
import java.util.UUID
import java.util.concurrent.Executors

/** Explicit debug workbench. Never merges its server ledger into the standalone UI mock. */
class SandboxPanel(
    private val activity: Activity,
    private val owner: String,
    private val isSafe: () -> Boolean,
    private val redraw: () -> Unit,
) {
    private val worker = operations
    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("sandbox_outbox", Activity.MODE_PRIVATE)
    private val vault = TokenVault(activity)
    private val api = SandboxApi("http://10.0.2.2:8787", sandboxEnabled = BuildConfig.DEBUG)
    private var login: SandboxLogin? = null
    private var wallet: Wallet? = null
    private var tickets = emptyList<RewardTicket>()
    private var busy = false
    private var closed = false
    private var generation = 0L
    private var playing = false
    private var message = "로컬 서버 연결 전 · OAuth/실제 광고 아님"
    private var textInk = 0xFF17352B.toInt()
    private fun key(name: String) = "$owner:$name"
    private fun saved(name: String) = prefs.getString(key(name), null)
    private fun put(name: String, value: String?) {
        check(prefs.edit().apply { if (value == null) remove(key(name)) else putString(key(name), value) }.commit())
    }
    private fun requestKey(name: String): String = saved(name) ?: UUID.randomUUID().toString().also { put(name, it) }

    fun pause() {
        generation++
        playing = false
        if (message.startsWith("합성 광고 재생")) message = "재생 중단 · 보상권 유지. 주차 후 다시 시작하세요."
    }

    fun close(revoke: Boolean = false) {
        if (closed) return
        pause()
        closed = true
        if (revoke) clearSavedSession(activity)
    }

    fun render(target: LinearLayout, ink: Int) {
        textInk = ink
        text(target, "로컬 서버 샌드박스")
        text(target, "10.0.2.2:8787 · 합성 거리/광고 · 실제 지급 없음")
        text(target, message)
        wallet?.let { text(target, "서버 확정 ${it.confirmedPoints}P · 대기 ${tickets.count { t -> t.status != "CREDITED" }}개") }
        if (login == null) {
            button(target, "로컬 시험 계정 연결") {
                runOperation {
                    if (prefs.getString("vaultOwner", null) != owner) vault.clear()
                    login = vault.load() ?: api.loginSandbox(SandboxIdentity(
                        "android-${owner.take(50)}", requestKey("installation"),
                    )).also {
                        vault.save(it)
                        check(prefs.edit().putString("vaultOwner", owner).commit())
                    }
                    refresh()
                    message = "연결됨 · 서버 원장 기준 · OAuth 아님"
                }
            }
            return
        }
        button(target, "서버 잔액·대기 새로고침") { runOperation { refresh(); message = "서버 조회 완료" } }
        button(target, "합성 거리 +1km 전송 / 실패 재시도") { submitDemoDistance() }
        for (ticket in tickets.filter { it.status != "CREDITED" }.take(10)) {
            val sid = saved("session:${ticket.id}")
            text(target, "${ticket.points}P · ${ticket.status}")
            val expired = saved("replay:${ticket.id}") == "true" ||
                (saved("expiry:${ticket.id}")?.toLongOrNull() ?: Long.MAX_VALUE) < System.currentTimeMillis() / 1000
            if (ticket.status == "AWAITING_CONFIRMATION" && sid != null && !expired) {
                button(target, "서버 확인하고 ${ticket.points}P 받기") {
                    runOperation {
                        val result = withAd(ticket.id) { api.claimReward(token(), ticket.id, sid, requestKey("claim:$sid")) }
                        refresh()
                        message = if (result.status == "CREDITED") "서버 원장에 한 번 적립됨" else "서버 완료 증명 대기"
                    }
                }
            } else if (ticket.status != "AWAITING_CONFIRMATION" || expired) {
                button(target, "합성 광고 6초 시작 · ${ticket.points}P") { beginAd(ticket) }
            } else {
                text(target, "이 기기에 완료 세션이 없습니다. 원래 시험 기기에서 확인하세요.")
            }
        }
    }

    private fun token(): String = checkNotNull(login).accessToken
    private fun refresh() {
        val nextWallet = api.wallet(token())
        check(nextWallet.accountId == login?.accountId)
        val nextTickets = api.tickets(token())
        wallet = nextWallet
        tickets = nextTickets
    }

    private fun submitDemoDistance() = runOperation {
        // Persist the entire operation identity before I/O; a lost response replays the same trip/chunk.
        val trip = requestKey("distanceTrip")
        val installation = checkNotNull(login).installationId
        api.startTrip(token(), TripBaseline(installation, trip, trip, 0, 0), "baseline-$trip")
        api.submitDistanceChunk(token(), DistanceChunk(installation, trip, 0, 0, 60_000, 1_000, 0, 1_000), "chunk-$trip")
        refresh()
        put("distanceTrip", null)
        message = "합성 1km 전송 완료 · CLIENT_REPORTED_UNVERIFIED"
    }

    private fun beginAd(ticket: RewardTicket) {
        if (playing) return
        val epoch = generation
        runOperation(after = {
            if (epoch != generation || !isSafe()) return@runOperation
            playing = true
            message = "합성 광고 재생 · 6초 · 실제 광고 아님"
            redraw()
            main.postDelayed({
                if (closed || !playing || epoch != generation || !isSafe()) return@postDelayed
                playing = false
                runOperation {
                    val sid = checkNotNull(saved("session:${ticket.id}"))
                    withAd(ticket.id) { api.completeSandboxAd(token(), sid, requestKey("complete:$sid")) }
                    refresh()
                    message = "합성 완료 증명 저장 · 사용자 확인 대기"
                }
            }, 6_000)
        }) {
            var sid = saved("session:${ticket.id}")
            val expiry = saved("expiry:${ticket.id}")?.toLongOrNull() ?: 0
            if (saved("replay:${ticket.id}") == "true" || (sid != null && System.currentTimeMillis() / 1000 > expiry)) {
                put("session:${ticket.id}", null)
                put("create:${ticket.id}", null)
                put("replay:${ticket.id}", null)
                sid = null
            }
            if (sid == null) {
                val session = api.createAdSession(token(), ticket.id, requestKey("create:${ticket.id}"))
                // Session identity survives response loss/restart and is never replaced by an unrelated proof.
                put("session:${ticket.id}", session.id)
                put("expiry:${ticket.id}", session.expiresAtEpochSeconds.toString())
                sid = session.id
            }
            withAd(ticket.id) { api.startAdSession(token(), sid, requestKey("start:$sid")) }
            refresh()
        }
    }

    private fun <T> withAd(ticketId: String, task: () -> T): T = try {
        task()
    } catch (error: SandboxApiException) {
        // Device wall time can be wrong. Server expiry must still enable an explicit rewatch.
        if (error.httpStatus == 410) put("replay:$ticketId", "true")
        throw error
    }

    private fun runOperation(after: (() -> Unit)? = null, task: () -> Unit) {
        if (busy || playing || closed || !isSafe()) return
        busy = true
        redraw()
        worker.execute {
            val result = runCatching(task)
            if ((result.exceptionOrNull() as? SandboxApiException)?.httpStatus == 401) {
                login = null
                wallet = null
                tickets = emptyList()
                runCatching { vault.clear() }
                prefs.edit().remove("vaultOwner").commit()
            }
            main.post {
                if (closed) return@post
                busy = false
                result.onFailure { error ->
                    message = if (error is SandboxApiException && error.httpStatus == 401) "로그인이 만료되었습니다 · 로컬 시험 계정을 다시 연결하세요"
                    else if (error is SandboxApiException) "서버 오류 ${error.httpStatus} / ${error.errorCode} · 같은 요청 재시도 가능"
                    else "로컬 서버에 연결할 수 없습니다 · 서버 실행과 주소를 확인하세요"
                }
                if (result.isSuccess) after?.invoke()
                redraw()
            }
        }
    }

    private fun text(target: LinearLayout, value: String) {
        target.addView(TextView(activity).apply {
            text = value
            textSize = 24f
            setTextColor(textInk)
            setPadding(12, 12, 12, 12)
        })
    }
    private fun button(target: LinearLayout, label: String, click: () -> Unit) {
        target.addView(Button(activity).apply {
            text = label
            textSize = 20f
            minHeight = (64 * resources.displayMetrics.density).toInt()
            isAllCaps = false
            isEnabled = !busy && !playing && isSafe()
            setOnClickListener { if (isSafe()) click() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    companion object {
        // Serializes vault ownership changes across Activity recreation/account replacement.
        private val operations = Executors.newSingleThreadExecutor()

        /** Logout also works after process restart, before the lazy panel is ever opened. */
        fun clearSavedSession(context: Context) {
            val appContext = context.applicationContext
            operations.execute {
                val vault = TokenVault(appContext)
                val saved = runCatching { vault.load() }.getOrNull()
                runCatching { vault.clear() }
                appContext.getSharedPreferences("sandbox_outbox", Context.MODE_PRIVATE)
                    .edit().remove("vaultOwner").commit()
                if (BuildConfig.DEBUG) runCatching {
                    saved?.let { SandboxApi("http://10.0.2.2:8787", true).logout(it.accessToken) }
                }
            }
        }
    }
}
