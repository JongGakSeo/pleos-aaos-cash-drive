package ai.cashdrive.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.cashdrive.core.rewards.RewardTicket
import ai.cashdrive.core.rewards.RewardWallet
import ai.cashdrive.core.rewards.TicketStatus
import ai.cashdrive.core.rewards.WalletSnapshot
import ai.cashdrive.core.safety.GearEvidence
import ai.cashdrive.core.safety.SafetyReducer
import ai.cashdrive.core.safety.SafetySnapshot
import ai.cashdrive.core.safety.SafetyState
import ai.cashdrive.app.network.SandboxPanel
import java.util.UUID

class MainActivity : Activity() {
    private enum class Page { SPLASH, LOGIN, INTRO, SKIN, HOME, SETTINGS, SANDBOX }

    private val reducer = SafetyReducer()
    private var gateway: CarSafetyGateway? = null
    private var safety = SafetySnapshot(0, 0, false, null, null, GearEvidence.UNKNOWN, SafetyState.UNKNOWN)
    private var page = Page.SPLASH
    private var initialPageAfterSafety: Page? = null
    private var account = ""
    private var walletAccount = ""
    private var currentWallet = RewardWallet("guest")
    private var skin = "horse"
    private var dark = false
    private var voiceConsent = false
    private var dontShowIntro = false
    private var tripPoints = 0
    private var demoGear = GearEvidence.UNKNOWN
    private var demoUnrestricted = false
    private var demoGeneration = 0L
    private var demoSequence = 0L
    private var adTicketId: String? = null
    private var adRunnable: Runnable? = null
    private var adGeneration = 0L
    private var adOfferVisible = false
    private var mockQrDialog: AlertDialog? = null
    private var sandboxPanel: SandboxPanel? = null
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("cash_drive_local", MODE_PRIVATE) }
    private val darkBackground = 0xFF20241F.toInt()
    private val dayBackground = 0xFFF2F1EA.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = preferences.getString("account", "").orEmpty()
        restoreAccountUiState()
        if (account.isNotEmpty()) loadWalletForAccount(account)
        page = when {
            account.isEmpty() -> Page.SPLASH
            !dontShowIntro -> Page.INTRO
            !preferences.getBoolean("skinSelected:$account", false) -> Page.SKIN
            else -> Page.HOME
        }
        if (page == Page.INTRO || page == Page.SKIN) initialPageAfterSafety = page
        render()
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEMO_SIGNALS) {
            demoGeneration = maxOf(demoGeneration + 1, safety.generation + 1)
            reducer.beginConnection(demoGeneration)
            publishDemoState()
        } else {
            gateway = CarSafetyGateway(this, reducer) { snapshot ->
                runOnUiThread { onSafetySnapshot(snapshot) }
            }
            gateway?.start()
        }
    }

    override fun onStop() {
        sandboxPanel?.pause()
        stopAd(interrupt = true)
        gateway?.stop()
        gateway = null
        if (BuildConfig.DEMO_SIGNALS) {
            onSafetySnapshot(reducer.invalidate(maxOf(demoGeneration + 1, safety.generation + 1)))
        }
        super.onStop()
    }

    override fun onDestroy() {
        sandboxPanel?.close()
        super.onDestroy()
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        render()
    }

    private fun restoreAccountUiState() {
        if (account.isEmpty()) return
        skin = preferences.getString("skin:$account", "horse") ?: "horse"
        dark = preferences.getBoolean("themeNight:$account", false)
        voiceConsent = preferences.getBoolean("voiceConsent:$account", false)
        dontShowIntro = preferences.getBoolean("dontShowIntro:$account", false)
        tripPoints = preferences.getInt("tripPoints:$account", 0).coerceAtLeast(0)
    }

    private fun loadWalletForAccount(id: String) {
        if (id.isBlank() || walletAccount == id) return
        val points = preferences.getInt("confirmed:$id", 0)
        val ticketRows = preferences.getString("tickets:$id", "").orEmpty()
        val tickets = ticketRows.split(';').filter { it.isNotBlank() }.mapNotNull { row ->
            val parts = row.split(':')
            if (parts.size != 3) null else runCatching {
                RewardTicket(parts[0], id, parts[1].toInt(), TicketStatus.valueOf(parts[2]))
            }.getOrNull()
        }
        currentWallet = runCatching { RewardWallet(id, WalletSnapshot(id, points, tickets)) }
            .getOrElse { RewardWallet(id) }
        walletAccount = id
        persistWallet()
    }

    private fun persistWallet() {
        if (account.isBlank() || walletAccount != account) {
            return
        }
        val state = currentWallet.snapshot()
        preferences.edit()
            .putInt("confirmed:$account", state.confirmedPoints)
            .putString("tickets:$account", state.tickets.joinToString(";") { "${it.id}:${it.points}:${it.status}" })
            .apply()
    }

    private fun switchAccount(id: String) {
        account = id
        walletAccount = ""
        preferences.edit().putString("account", id).apply()
        restoreAccountUiState()
        loadWalletForAccount(id)
        adOfferVisible = false
    }

    private fun publishDemoState() {
        if (!BuildConfig.DEMO_SIGNALS) return
        demoSequence += 1
        reducer.onConnected(demoGeneration, demoSequence)
        demoSequence += 1
        reducer.onUxRestrictions(demoGeneration, demoSequence, !demoUnrestricted, if (demoUnrestricted) 0 else 1)
        demoSequence += 1
        onSafetySnapshot(reducer.onGear(demoGeneration, demoSequence, demoGear))
    }

    private fun onSafetySnapshot(snapshot: SafetySnapshot) {
        val previous = safety.state
        safety = snapshot
        if (safety.mayOpenAd) {
            initialPageAfterSafety?.let { page = it }
            initialPageAfterSafety = null
        }
        if (previous == SafetyState.PARKED_ALLOWED && !safety.mayOpenAd) stopAd(interrupt = true)
        if (!safety.mayOpenAd && page in setOf(Page.LOGIN, Page.INTRO, Page.SKIN, Page.SETTINGS, Page.SANDBOX)) {
            sandboxPanel?.pause()
            mockQrDialog?.dismiss()
            mockQrDialog = null
            page = Page.HOME
            adOfferVisible = false
        }
        render()
    }

    private fun stopAd(interrupt: Boolean) {
        adGeneration += 1
        adRunnable?.let(handler::removeCallbacks)
        adRunnable = null
        if (interrupt) {
            adTicketId?.let { id ->
                runCatching { currentWallet.interruptAd(id) }
                persistWallet()
            }
        }
        adTicketId = null
    }

    private fun beginDemoDistanceBlock() {
        if (!BuildConfig.DEMO_SIGNALS || !safety.mayOpenAd || account.isBlank()) return
        currentWallet.addPending("demo-${UUID.randomUUID()}", 10)
        tripPoints += 10
        preferences.edit().putInt("tripPoints:$account", tripPoints).apply()
        persistWallet()
        render()
    }

    override fun onBackPressed() {
        sandboxPanel?.pause()
        if (page == Page.HOME) {
            if (safety.mayOpenAd) moveTaskToBack(true)
            return
        }
        if (!safety.mayOpenAd) {
            page = Page.HOME
        } else {
            page = when (page) {
                Page.LOGIN -> Page.SPLASH
                Page.INTRO -> Page.LOGIN
                Page.SKIN -> Page.INTRO
                Page.SETTINGS, Page.SANDBOX -> Page.HOME
                else -> Page.HOME
            }
        }
        render()
    }

    private fun render() {
        if (!safety.mayOpenAd && page in setOf(Page.LOGIN, Page.INTRO, Page.SKIN, Page.SETTINGS, Page.SANDBOX)) page = Page.HOME
        val background = if (dark) darkBackground else dayBackground
        val ink = if (dark) 0xFFF1F1E9.toInt() else 0xFF17352B.toInt()
        val muted = if (dark) 0xFFC9D0C1.toInt() else 0xFF62685C.toInt()
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val wide = resources.configuration.screenWidthDp >= 900
        val outer = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setBackgroundColor(background)
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(dp(24) + bars.left, dp(18) + bars.top, dp(24) + bars.right, dp(18) + bars.bottom)
                } else {
                    view.setPadding(dp(24) + insets.systemWindowInsetLeft, dp(18) + insets.systemWindowInsetTop,
                        dp(24) + insets.systemWindowInsetRight, dp(18) + insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(outer)
        outer.requestApplyInsets()
        val leftScroll = ScrollView(this).apply { isFillViewport = true }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(16))
        }
        leftScroll.addView(main, FrameLayout.LayoutParams(-1, -2))
        outer.addView(leftScroll, if (wide) LinearLayout.LayoutParams(0, -1, 1.1f) else LinearLayout.LayoutParams(-1, -1))
        val sideScroll = ScrollView(this).apply { isFillViewport = true }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(4), dp(16))
        }
        sideScroll.addView(side, FrameLayout.LayoutParams(-1, -2))
        if (wide) outer.addView(sideScroll, LinearLayout.LayoutParams(0, -1, .9f))

        when (page) {
            Page.SPLASH -> renderSplash(main, ink, muted)
            Page.LOGIN -> renderLogin(main, ink, muted)
            Page.INTRO -> renderIntro(main, ink, muted)
            Page.SKIN -> renderSkin(main, ink, muted)
            Page.HOME -> renderHome(main, if (wide) side else main, ink, muted, wide)
            Page.SETTINGS -> renderSettings(main, ink, muted)
            Page.SANDBOX -> {
                if (BuildConfig.DEBUG && account.isNotBlank() && safety.mayOpenAd) {
                    if (sandboxPanel == null) sandboxPanel = SandboxPanel(this, account,
                        { safety.mayOpenAd && page == Page.SANDBOX && !isFinishing && !isDestroyed }, { render() })
                    sandboxPanel?.render(main, ink)
                    action(main, "샌드박스 닫기", false) { sandboxPanel?.pause(); page = Page.HOME; render() }
                }
            }
        }
    }

    private fun renderSplash(target: LinearLayout, ink: Int, muted: Int) {
        title(target, "Cash Drive", ink, 36)
        body(target, "주행 보상 후보를 안전한 시점에 확인하는 로컬 앱 데모입니다.", muted)
        target.addView(ImageView(this).apply {
            setImageResource(R.drawable.cash_drive_icon)
            contentDescription = "Cash Drive 앱 아이콘"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(18); bottomMargin = dp(14) })
        action(target, "시작하기", true, enabled = safety.mayOpenAd || BuildConfig.DEMO_SIGNALS) {
            if (safety.mayOpenAd) page = Page.LOGIN else if (BuildConfig.DEMO_SIGNALS) page = Page.HOME
            render()
        }
        if (BuildConfig.DEMO_SIGNALS && !safety.mayOpenAd) renderDemoSignalControls(target, muted)
    }

    private fun renderLogin(target: LinearLayout, ink: Int, muted: Int) {
        title(target, "휴대폰 계정 연결", ink, 32)
        body(target, "인증 서버가 연결되지 않았습니다. QR은 실제 로그인이 아닌 화면 흐름 시뮬레이션입니다.", muted)
        if (BuildConfig.BUILD_TYPE == "release") {
            listOf("네이버", "카카오", "Google").forEach { action(target, "$it 로그인 · 연결 전", false, enabled = false) {} }
        } else if (BuildConfig.DEMO_SIGNALS) {
            listOf("네이버", "카카오", "Google").forEach { provider ->
                action(target, "$provider · QR 로그인 데모", false) { showMockQr(provider) }
            }
        } else {
            body(target, "에뮬레이터용 로컬 계정이며 OAuth는 연결되어 있지 않습니다.", muted)
            action(target, "로컬 체험 계정", true) { finishLogin("emu-local") }
        }
    }

    private fun showMockQr(provider: String) {
        if (!safety.mayOpenAd) return
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }
        val qr = MockQrIllustration(this).apply { contentDescription = "실제 QR이 아닌 로그인 흐름 설명용 무늬" }
        dialogContent.addView(qr, LinearLayout.LayoutParams(dp(180), dp(180)).apply { gravity = android.view.Gravity.CENTER })
        body(dialogContent, "QR 모양 일러스트 · 실제 스캔 불가\n$provider 로그인은 로컬 데모입니다.", 0xFF62685C.toInt())
        mockQrDialog = AlertDialog.Builder(this)
            .setTitle("휴대폰 로그인 시뮬레이션")
            .setView(dialogContent)
            .setNegativeButton("나중에") { _, _ -> }
            .setPositiveButton("데모 계정으로 계속") { _, _ -> finishLogin("${provider.lowercase()}-demo") }
            .create().also { dialog ->
                dialog.setOnDismissListener { mockQrDialog = null }
                dialog.show()
            }
    }

    private fun finishLogin(id: String) {
        if (!safety.mayOpenAd) return
        switchAccount(id)
        val skinSelected = preferences.getBoolean("skinSelected:$account", false)
        page = when {
            !dontShowIntro -> Page.INTRO
            !skinSelected -> Page.SKIN
            else -> Page.HOME
        }
        render()
    }

    private fun renderIntro(target: LinearLayout, ink: Int, muted: Int) {
        title(target, "안전한 시점에 확인", ink, 32)
        body(target, "주행 화면은 정적입니다. 광고와 적립 확인은 UXR 비제한 + 기어 P에서만 열립니다. 플랫폼 신호를 받지 못하면 잠긴 상태로 유지합니다.", muted)
        val checkbox = CheckBox(this).apply {
            text = "다시 보지 않기"
            setTextColor(ink)
            textSize = 18f
            minHeight = dp(64)
            isChecked = dontShowIntro
        }
        target.addView(checkbox, LinearLayout.LayoutParams(-1, -2))
        action(target, "계속", true) {
            dontShowIntro = checkbox.isChecked
            preferences.edit().putBoolean("dontShowIntro:$account", dontShowIntro).apply()
            page = if (preferences.getBoolean("skinSelected:$account", false)) Page.HOME else Page.SKIN
            render()
        }
        action(target, "건너뛰기", false) {
            page = if (preferences.getBoolean("skinSelected:$account", false)) Page.HOME else Page.SKIN
            render()
        }
    }

    private fun renderSkin(target: LinearLayout, ink: Int, muted: Int) {
        title(target, "정적 스킨 선택", ink, 32)
        body(target, "주행 중에는 그림이 움직이지 않습니다.", muted)
        listOf("horse" to "말", "mine" to "광산", "gpu" to "서버 작업").forEach { (id, label) ->
            action(target, if (skin == id) "선택됨 · $label" else label, skin == id) {
                if (!safety.mayOpenAd) return@action
                skin = id
                preferences.edit().putString("skin:$account", skin).putBoolean("skinSelected:$account", true).apply()
                render()
            }
        }
        action(target, "선택 완료", true) {
            if (!safety.mayOpenAd) return@action
            preferences.edit().putBoolean("skinSelected:$account", true).apply()
            page = Page.HOME
            render()
        }
    }

    private fun renderHome(main: LinearLayout, actions: LinearLayout, ink: Int, muted: Int, wide: Boolean) {
        if (account.isNotEmpty()) loadWalletForAccount(account)
        title(main, if (account.isBlank()) "Cash Drive" else "안녕하세요", ink, 30)
        body(main, when (safety.state) {
            SafetyState.PARKED_ALLOWED -> "주차 확인 · UXR 비제한"
            SafetyState.RESTRICTED -> "주행 제한 · 현황을 정적으로 표시"
            SafetyState.STOPPED_UNQUALIFIED -> "기어 P 확인 전 · 민감한 기능 잠금"
            SafetyState.UNKNOWN -> "안전 상태 확인 중 · 민감한 기능 잠금"
        }, muted)
        main.addView(SkinArtView(this).apply { setSkin(skin); setNight(dark); contentDescription = "$skin 선택 스킨 정적 벡터 그림" },
            LinearLayout.LayoutParams(-1, if (wide) dp(300) else dp(210)).apply { topMargin = dp(10); bottomMargin = dp(8) })
        val wallet = currentWallet.snapshot()
        val earned = wallet.confirmedPoints
        val pending = wallet.pendingPoints
        metric(main, "확정 포인트", "$earned P", ink, muted)
        metric(main, "적립 대기 · 이번 주행", "$pending P · $tripPoints P", ink, muted)
        val adsNeeded = wallet.tickets.count { it.status == TicketStatus.PENDING || it.status == TicketStatus.AD_PLAYING }
        val confirmations = wallet.tickets.count { it.status == TicketStatus.AWAITING_CONFIRMATION }
        metric(main, "남은 광고 · 완료 확인 대기", "${adsNeeded}개 · ${confirmations}개", ink, muted)

        if (account.isBlank() && safety.mayOpenAd) {
            action(actions, "로그인", true) { if (safety.mayOpenAd) { page = Page.LOGIN; render() } }
        } else if (safety.mayOpenAd) {
            renderParkedActions(actions, muted)
        } else {
            body(actions, "주행 중에는 포인트·스킨 현황만 확인할 수 있습니다.", muted)
        }
        if (BuildConfig.DEMO_SIGNALS) renderDemoSignalControls(actions, muted)
    }

    private fun renderParkedActions(target: LinearLayout, muted: Int) {
        if (adTicketId != null) {
            body(target, "광고 데모 재생 중 · 완료 뒤 직접 확인하세요", muted)
            action(target, "광고 중단 · 보상권 보관", false) { stopAd(interrupt = true); render() }
            return
        }
        if (BuildConfig.DEMO_SIGNALS && account.isNotBlank()) {
            action(target, "데모 거리 +1km → 대기 10P", false) { beginDemoDistanceBlock() }
            action(target, "다음 주행 데모 · 대기 보상 이월", false) {
                tripPoints = 0
                preferences.edit().putInt("tripPoints:$account", 0).apply()
                render()
            }
        }
        if (BuildConfig.DEMO_SIGNALS) {
            action(target, "광고 안내 · 10P · 5초 데모", true) { adOfferVisible = true; render() }
        } else {
            body(target, "광고 공급자 미연결 · 지급 적립은 데모 변형에서만 시뮬레이션합니다.", muted)
        }
        if (adOfferVisible && BuildConfig.DEMO_SIGNALS) {
            body(target, "10P 보상 조건: 광고 데모 완료 후 ‘확인하고 적립’을 눌러야 반영됩니다. 광고는 자동 재생되지 않습니다.", muted)
            action(target, "광고 보고 적립", true, enabled = BuildConfig.DEMO_SIGNALS) { startDemoAd(target, muted) }
            action(target, "나중에 보기", false) { adOfferVisible = false; render() }
        }
        currentWallet.snapshot().tickets.filter { it.status == TicketStatus.AWAITING_CONFIRMATION }.forEach { ticket ->
            body(target, "완료 확인 대기 · ${ticket.points}P", muted)
            action(target, "확인하고 적립 ${ticket.points}P", true, enabled = BuildConfig.DEMO_SIGNALS) {
                if (!safety.mayOpenAd || !BuildConfig.DEMO_SIGNALS) return@action
                runCatching { currentWallet.confirm(ticket.id, safety.state, online = true) }
                persistWallet()
                render()
            }
        }
        action(target, "스킨 선택", false) { if (safety.mayOpenAd) { page = Page.SKIN; render() } }
        action(target, "설정", false) { if (safety.mayOpenAd) { page = Page.SETTINGS; render() } }
    }

    private fun startDemoAd(target: LinearLayout, muted: Int) {
        if (!BuildConfig.DEMO_SIGNALS || !safety.mayOpenAd || account.isBlank() || adTicketId != null) return
        val ticket = currentWallet.snapshot().tickets.firstOrNull { it.status == TicketStatus.PENDING }
        if (ticket == null) {
            body(target, "대기 중인 보상권이 없습니다. 데모 거리 1km를 추가할 수 있습니다.", muted)
            return
        }
        runCatching { currentWallet.beginAd(ticket.id, safety.state) }.onFailure { return }
        persistWallet()
        adTicketId = ticket.id
        val token = ++adGeneration
        val status = body(target, "광고 데모 재생 · 5초", muted)
        var remaining = 5
        val task = object : Runnable {
            override fun run() {
                if (token != adGeneration || adTicketId != ticket.id) return
                if (!safety.mayOpenAd || !BuildConfig.DEMO_SIGNALS) {
                    stopAd(interrupt = true)
                    return
                }
                if (remaining > 0) {
                    status.text = "광고 데모 재생 · ${remaining}초"
                    remaining -= 1
                    handler.postDelayed(this, 1_000L)
                } else {
                    runCatching { currentWallet.adCompleted(ticket.id, safety.state) }
                    adTicketId = null
                    adRunnable = null
                    persistWallet()
                    render()
                }
            }
        }
        adRunnable = task
        handler.postDelayed(task, 1_000L)
    }

    private fun renderDemoSignalControls(target: LinearLayout, muted: Int) {
        if (!BuildConfig.DEMO_SIGNALS) return
        body(target, "데모 안전 신호 · 실제 차량 신호 아님", muted)
        action(target, if (demoUnrestricted) "UXR 비제한 데모 · 제한으로 전환" else "UXR 제한 데모 · 비제한으로 전환", false) {
            demoUnrestricted = !demoUnrestricted
            publishDemoState()
        }
        action(target, "기어 데모: ${when (demoGear) { GearEvidence.UNKNOWN -> "미확인"; GearEvidence.PARK -> "P"; GearEvidence.NOT_PARK -> "주행" }} · 다음", false) {
            demoGear = when (demoGear) { GearEvidence.UNKNOWN -> GearEvidence.PARK; GearEvidence.PARK -> GearEvidence.NOT_PARK; else -> GearEvidence.UNKNOWN }
            publishDemoState()
        }
    }

    private fun renderSettings(target: LinearLayout, ink: Int, muted: Int) {
        title(target, "설정", ink, 32)
        body(target, "계정: ${account.ifBlank { "게스트" }} · 로컬 저장", muted)
        if (BuildConfig.DEBUG && account.isNotBlank()) {
            action(target, "로컬 서버 샌드박스", false) { if (safety.mayOpenAd) { page = Page.SANDBOX; render() } }
            action(target, "Godot 스킨 실험 · 실제 주차 신호 필요", false) {
                if (safety.mayOpenAd) startActivity(android.content.Intent().setClassName(this, "ai.cashdrive.app.RendererActivity")
                    .putExtra("skin", skin).putExtra("night", dark))
            }
        }
        action(target, if (dark) "야간 테마 · 낮 테마로 변경" else "낮 테마 · 야간 테마로 변경", false) {
            dark = !dark
            preferences.edit().putBoolean("themeNight:$account", dark).apply()
            render()
        }
        val voice = CheckBox(this).apply {
            text = "음성 광고 실험 동의 (실제 음성 기능 없음)"
            setTextColor(ink)
            textSize = 18f
            minHeight = dp(64)
            isChecked = voiceConsent
            setOnCheckedChangeListener { _, checked ->
                voiceConsent = checked
                preferences.edit().putBoolean("voiceConsent:$account", checked).apply()
            }
        }
        target.addView(voice, LinearLayout.LayoutParams(-1, -2))
        action(target, "설명 다시 보기", false) { if (safety.mayOpenAd) { page = Page.INTRO; render() } }
        action(target, "로그아웃", false) {
            if (!safety.mayOpenAd) return@action
            stopAd(interrupt = true)
            persistWallet()
            val oldPanel = sandboxPanel
            if (oldPanel != null) oldPanel.close(revoke = true) else SandboxPanel.clearSavedSession(this)
            sandboxPanel = null
            account = ""
            walletAccount = ""
            currentWallet = RewardWallet("guest")
            skin = "horse"
            dark = false
            voiceConsent = false
            dontShowIntro = false
            tripPoints = 0
            adOfferVisible = false
            preferences.edit().remove("account").apply()
            page = Page.SPLASH
            render()
        }
        action(target, "홈", true) { if (safety.mayOpenAd) { page = Page.HOME; render() } }
    }

    private fun metric(target: LinearLayout, label: String, value: String, ink: Int, muted: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(if (dark) 0xFF303930.toInt() else 0xFFE6E8D9.toInt())
        }
        body(card, label, muted)
        title(card, value, ink, 28)
        target.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun action(target: LinearLayout, label: String, primary: Boolean, enabled: Boolean = true, click: () -> Unit) =
        android.widget.Button(this).apply {
            text = label
            textSize = 18f
            minHeight = dp(64)
            isAllCaps = false
            isEnabled = enabled
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) 0xFF315C44.toInt() else if (dark) 0xFF364437.toInt() else 0xFFE0E5D5.toInt())
            setTextColor(if (primary || dark) 0xFFF5F5EC.toInt() else 0xFF17352B.toInt())
            setOnClickListener { click() }
            target.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

    private fun title(target: LinearLayout, text: String, color: Int, size: Int) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(8), 0, dp(6))
        target.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun body(target: LinearLayout, text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
        target.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class MockQrIllustration(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height).toFloat()
            paint.color = 0xFFF0F0E8.toInt(); canvas.drawRoundRect(0f, 0f, size, size, 12f, 12f, paint)
            paint.color = 0xFF17352B.toInt()
            val cell = size / 12f
            val cells = listOf(
                1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 3, 3 to 1, 3 to 2, 3 to 3,
                1 to 8, 1 to 9, 1 to 10, 2 to 8, 2 to 10, 3 to 8, 3 to 9, 3 to 10,
                8 to 1, 8 to 2, 8 to 3, 9 to 1, 9 to 3, 10 to 1, 10 to 2, 10 to 3,
                5 to 5, 5 to 7, 6 to 4, 6 to 6, 7 to 5, 8 to 6, 9 to 8, 10 to 10,
            )
            cells.forEach { (x, y) -> canvas.drawRect(x * cell, y * cell, (x + .72f) * cell, (y + .72f) * cell, paint) }
            paint.color = 0xFFB6CC7E.toInt()
            canvas.drawRect(4 * cell, 8 * cell, 7 * cell, 9 * cell, paint)
        }
    }
}
