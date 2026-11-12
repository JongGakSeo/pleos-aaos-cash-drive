package ai.cashdrive.app.home

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import ai.cashdrive.app.BuildConfig
import ai.cashdrive.app.pleos.NaviSnapshot
import ai.cashdrive.app.pleos.NaviSource
import ai.cashdrive.app.pleos.PleosVehicleSnapshot
import ai.cashdrive.app.pleos.PleosVehicleSource
import ai.cashdrive.app.render.PreviewPlugin
import ai.cashdrive.app.session.PlaybackSessionHost
import ai.cashdrive.core.playback.OdometerCrossCheck
import ai.cashdrive.core.playback.OdometerState
import ai.cashdrive.core.playback.PlaybackState
import ai.cashdrive.core.playback.VehicleState
import ai.cashdrive.core.playback.VehicleStatus
import ai.cashdrive.core.session.ThemeIds
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

/**
 * The home: Godot horizon scene with Pleos-style native panels on top. Drives the scene through
 * run (driving), stand (waiting, parked) and reveal (arriving in park after a trip), autoplays the
 * briefings only while the session allows playback, and shows Pleos SDK values as context only.
 * Safety and rewards stay in the session; nothing read from the SDK adapters reaches the gate or the
 * ledger. Runs in its own process so the engine initializes once and the process ends with the screen.
 */
class HomeActivity : FragmentActivity(), GodotHost, HomeView.Actions, AdPlayer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var view: HomeView
    private lateinit var host: PlaybackSessionHost
    private lateinit var autoplay: AdPlayer
    private var subscription: AutoCloseable? = null
    private var state: PlaybackState? = null
    private var adProgress: AdPlayer.Progress? = null
    private var permissionAsked = false
    private var fragment: GodotFragment? = null
    private var plugin: PreviewPlugin? = null
    private var generation = 0L
    private var lastSceneKey: String? = null
    private var resumed = false
    private var loopStarted = false
    private var enginePaused = false
    private var attaching = false
    private var lastTick = 0L
    /** The arrival sequence (design §7) holds the stage: the scene runs its reveal and the player waits. */
    private var revealing = false
    /** The welcome card holds the stage before the welcome ticket's first playback (design §13-4). */
    private var welcoming = false
    /**
     * A reward sequence is due but the engine has not started yet (a cold start): the player waits and the
     * sequence starts with the scene, so its cards are not spent behind the launch's blank frames.
     */
    private var stageWaiting = false
    /** The engine did not start within [STAGE_WAIT_MS] of resuming: sequences start without it. */
    private var stageGaveUp = false
    /** Today's issue count when this trip began to drive; cleared at the arrival's hand-off (kept in prefs). */
    private var tripMark: TripMark? = null
    /** The charge-bonus card holds the stage before the theater opens on the bonus ticket. */
    private var bonusing = false
    /** A charge bonus whose card has not played yet: it waits for a free stage (after a card, a page). */
    private var bonusDue = false
    /** A completion's points and the total they made: the halo waits until the meter shows that total. */
    private var creditBurst: Pair<Int, Int>? = null
    private var arrivalRefresh: Runnable? = null
    // Pleos SDK context (display only). Both stay null in demo builds, which never start the adapters.
    private var pleosVehicle: PleosVehicleSource? = null
    private var navi: NaviSource? = null
    private var vehicleInfo = PleosVehicleSnapshot()
    private var naviInfo = NaviSnapshot()
    private val crossCheck = OdometerCrossCheck()
    private var odometer = OdometerState()
    private var lastOdometerKm: Float? = null
    private var crossCheckBaseMeters: Long? = null
    private val prefs by lazy { getSharedPreferences("cash_drive_home_v1", MODE_PRIVATE) }
    private val creditsLog by lazy { CreditsLog(this) }
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * Advances the ticket every [TICK_MS] while it plays. A video creative advances it by what the picture
     * played (nothing while it loads, the rest once the file ends); anything else, including a video that
     * failed and gave way to the stand-in card, runs on the wall clock. AdPlayer still owns the position.
     */
    private val stageTimeout = Runnable {
        if (loopStarted) return@Runnable
        stageGaveUp = true
        if (stageWaiting) onState(host.ui.state)
    }

    /** Republishes the scene when the minute turns, so the day theme turns to dusk and back on time. */
    private val minuteTick = Runnable { publishScene(); scheduleMinuteTick() }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val wall = now - lastTick
            lastTick = now
            autoplay.tick(if (view.videoActive) view.consumeDeltaMs() else wall)
            if (resumed && autoplay.playing) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Never restore a fragment that could start a second engine in this process.
        super.onCreate(null)
        if (Application.getProcessName() != "$packageName:home" || engineClaimed.get()) {
            finish(); return
        }
        // Opened by PermissionGateActivity after its dialog: do not send the driver back to it unasked.
        permissionAsked = intent?.getBooleanExtra(PermissionGateActivity.EXTRA_PERMISSION_ASKED, false) == true
        view = HomeView(this, this)
        view.setLock("차량 상태를 확인하고 있어요", "확인되면 바로 이어서 보여 드려요")
        setContentView(view.root)
        host = PlaybackSessionHost(this, ACCOUNT)
        autoplay = AdPlayer(host.ui, this)
        tripMark = TripMark.decode(prefs.getString(PREF_TRIP_MARK, null))
        view.setLockAction("권한 허용") { askSpeedPermission(force = true) }
        if (BuildConfig.DEMO_SIGNALS) {
            // Synthetic vehicle states for presentations: debug builds of the demo flavor only.
            if (BuildConfig.DEBUG) view.addNavChip("테스트", "테스트 도구") { showDemoTools() }
        } else {
            // Real-signal builds read the Pleos SDKs for context lines and navigation actions only.
            pleosVehicle = PleosVehicleSource(this) { onVehicleInfo(it) }
            navi = NaviSource(this) { onNaviInfo(it) }
        }
        view.root.setOnApplyWindowInsetsListener { root, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.sceneHost.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) publishScene()
        }
        // The theater has faded in over the running scene: only now may the engine stop under it.
        view.onTheaterCovered = { publishScene() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.backInPage()) return
                if (view.pageOpen) onCloseOverlay() else moveTaskToBack(true)
            }
        })
        applyLook()
    }

    override fun onStart() {
        super.onStart()
        if (!::host.isInitialized) return
        host.start()
        subscription = host.ui.observe { snapshot -> main.post { onState(snapshot) } }
        pleosVehicle?.start()
        navi?.start()
        askSpeedPermission(force = false)
    }

    /**
     * Speed is a runtime permission on AAOS; without it the vehicle state stays unknown and locked. The dialog
     * is shown by PermissionGateActivity in its own task, never on top of this one (see that class); the result
     * arrives through onResume -> refreshSpeedPermission when this task comes back to the front.
     */
    private fun askSpeedPermission(force: Boolean) {
        if (BuildConfig.DEMO_SIGNALS || host.speedPermissionGranted) return
        if (permissionAsked && !force) return
        permissionAsked = true
        startActivity(PermissionGateActivity.intent(this))
    }

    override fun onResume() {
        super.onResume()
        if (!::host.isInitialized) return
        resumed = true
        host.refreshSpeedPermission()
        // The live state, not the last posted one: a reward card due now starts before the player is
        // released, so the theater never flashes ahead of it.
        onState(host.ui.state)
        startTicker()
        attachAfterDraw()
        publishScene()
        scheduleMinuteTick()
        if (!loopStarted) { main.removeCallbacks(stageTimeout); main.postDelayed(stageTimeout, STAGE_WAIT_MS) }
    }

    override fun onPause() {
        if (::host.isInitialized) {
            resumed = false
            // A sequence nobody sees is not acknowledged: it plays again when the home is back in front.
            cancelRewards()
            autoplay.hold(true)
            main.removeCallbacks(ticker)
            main.removeCallbacks(minuteTick)
            main.removeCallbacks(stageTimeout)
            publishScene()
        }
        super.onPause()
    }

    override fun onStop() {
        if (::host.isInitialized) autoplay.hold(true)
        subscription?.close(); subscription = null
        pleosVehicle?.stop()
        navi?.stop()
        if (::host.isInitialized) host.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cancelRewards()
        arrivalRefresh?.let(main::removeCallbacks); arrivalRefresh = null
        pleosVehicle?.close(); pleosVehicle = null
        navi?.close(); navi = null
        if (::host.isInitialized) host.close()
        plugin?.publish("", false)
        super.onDestroy()
        if (Application.getProcessName() == "$packageName:home" && engineClaimed.get()) Process.killProcess(Process.myPid())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::view.isInitialized) { applyLook(); publishScene() }
    }

    /** Libraries may lock orientation; Pleos panels must stay resizable in both directions. */
    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    private fun onState(snapshot: PlaybackState) {
        val previous = state
        state = snapshot
        view.setSkin(skin())
        stageWaiting = false
        if (!menusAllowed(snapshot) && view.pageOpen) view.closeOverlay()
        // Leaving park mid-sequence (Review Focus 4): the cards go at once, the reveal stays unacknowledged
        // and the theater never opens. Outside park nothing of the sequence may move anyway.
        if (!snapshot.playbackAllowed) cancelRewards()
        markTrip(snapshot)
        maybeReveal(snapshot)
        maybeWelcome(snapshot)
        noticeChargeBonus(previous, snapshot)
        maybeBonus(snapshot)
        holdPlayer()
        autoplay.onState(snapshot)
        startTicker()
        render()
    }

    /** The player waits while the home is not in front, a page covers it or a reward card holds (or waits for) the stage. */
    private fun holdPlayer() =
        autoplay.hold(!resumed || view.pageOpen || revealing || welcoming || bonusing || stageWaiting)

    /**
     * A sequence may start once the engine runs (its main loop started and the scene is on screen), or once
     * it failed to start in time. Until then the sequence is due and the player is held ([stageWaiting]).
     */
    private fun stageReady(): Boolean {
        if (loopStarted || stageGaveUp) return true
        stageWaiting = true
        return false
    }

    /**
     * Arriving in park after a trip (design §7, §13-4): hold the player and play the arrival over the scene's
     * reveal: card 1 with today's distance, card 2 with this trip's tickets (counted from the [tripMark]),
     * the carried-over ones or the welcome ticket. At the hand-off (2.6 s) the session clears the request
     * and the briefing starts; with nothing to watch card 1 stands alone and nothing plays after it. The
     * session's live state has the last word: a snapshot posted before an acknowledgement never replays it.
     */
    private fun maybeReveal(snapshot: PlaybackState) {
        if (revealing || !resumed || !snapshot.trip.revealPending || !snapshot.playbackAllowed) return
        val live = host.ui.state
        if (!live.trip.revealPending || !live.playbackAllowed) return
        if (!stageReady()) return
        if (welcoming) cancelRewards()
        revealing = true
        autoplay.hold(true)
        val trip = live.trip
        val tickets = RewardScript.ticketsThisTrip(tripMark, trip.issuedToday, today())
        val welcome = RewardScript.welcomeDue(live.creditedCount, live.currentAd, welcomed())
        view.playArrival(trip.todayMeters, tickets, live.remainingAdCount, welcome) { onArrivalHandOff(welcome) }
        publishScene()
    }

    private fun onArrivalHandOff(welcomeNext: Boolean) {
        revealing = false
        setTripMark(null)
        // The theater opens on the unplayed welcome ticket from here: the welcome card is no longer due.
        if (welcomeNext) markWelcomed()
        host.acknowledgeReveal()
        handOff()
    }

    /**
     * Parked without a trip before the welcome ticket's first playback of a fresh install: "환영 시청권 1장"
     * for 0.8 s, then the theater (design §13-4). Once it finished it never comes again. On a fresh launch it
     * waits for the engine (the player held meanwhile), and its 0.8 s count from its first frame.
     */
    private fun maybeWelcome(snapshot: PlaybackState) {
        if (welcoming || revealing || bonusing || !resumed || !snapshot.playbackAllowed || snapshot.trip.revealPending) return
        if (adProgress != null || view.pageOpen) return
        val live = host.ui.state
        if (!live.playbackAllowed || live.trip.revealPending) return
        if (!RewardScript.welcomeDue(live.creditedCount, live.currentAd, welcomed())) return
        if (!stageReady()) return
        welcoming = true
        autoplay.hold(true)
        view.playWelcome {
            welcoming = false
            markWelcomed()
            handOff()
        }
    }

    /** The stage is the player's again: a charge bonus held back by the cards plays its card first, then the theater may open. */
    private fun handOff() {
        maybeBonus(host.ui.state)
        holdPlayer()
        startTicker()
        render()
    }

    /** Ends a running arrival or welcome card and any completion burst; nothing is acknowledged or handed off. */
    private fun cancelRewards() {
        revealing = false
        welcoming = false
        bonusing = false
        bonusDue = false
        creditBurst = null
        if (::view.isInitialized) view.cancelRewards()
    }

    /** Takes the trip's mark when driving starts and keeps it through lights (design §13-4). */
    private fun markTrip(snapshot: PlaybackState) {
        val next = RewardScript.mark(tripMark, snapshot.vehicleState, snapshot.trip.issuedToday, today())
        if (next != tripMark) setTripMark(next)
    }

    private fun setTripMark(mark: TripMark?) {
        tripMark = mark
        prefs.edit().apply { if (mark == null) remove(PREF_TRIP_MARK) else putString(PREF_TRIP_MARK, mark.encode()) }.apply()
    }

    private fun welcomed() = prefs.getBoolean(PREF_WELCOMED, false)
    private fun markWelcomed() = prefs.edit().putBoolean(PREF_WELCOMED, true).apply()

    /** The device-local day in the session's format ("YYYYMMDD"), the same key its daily counts use. */
    private fun today(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * The parked charge bonus: one more ticket appeared together with a confirmed charge port in park. It
     * gets its reward card ("충전 보너스 1장", [maybeBonus]) before the theater opens on it, the way the
     * welcome ticket does; a toast there would sit on the theater's caption. With a briefing already on
     * screen the ticket simply joins the queue and the meter's stubs show it.
     */
    private fun noticeChargeBonus(previous: PlaybackState?, next: PlaybackState) {
        if (previous == null || next.pendingAdIds.size <= previous.pendingAdIds.size) return
        if (next.vehicleState != VehicleState.STOPPED_PARKED || next.trip.charging != true) return
        if (previous.trip.charging == true && previous.vehicleState == VehicleState.STOPPED_PARKED) return
        if (adProgress == null) bonusDue = true
    }

    /**
     * Plays the due charge-bonus card once the stage is free (parked and allowed, in front, no page, no other
     * card, no briefing on screen): the player waits for its 1.0 s, then the theater opens on the bonus ticket.
     */
    private fun maybeBonus(snapshot: PlaybackState) {
        if (!bonusDue || bonusing || revealing || welcoming || !resumed || !snapshot.playbackAllowed) return
        if (adProgress != null || view.pageOpen) return
        if (!stageReady()) return
        bonusDue = false
        bonusing = true
        autoplay.hold(true)
        view.playChargeBonus {
            bonusing = false
            handOff()
        }
    }

    private fun render() {
        val current = state ?: return
        val trip = current.trip
        val parked = current.vehicleState == VehicleState.STOPPED_PARKED
        view.setMotionLevel(motionLevel(current))
        view.setMeter(trip.tripMeters, trip.todayMeters, trip.nextTicketMeters, current.remainingAdCount,
            trip.issuedToday, trip.dailyCap, current.totalPoints, parked)
        // The completion halo goes with the count-up it surrounds: the first render that shows the new total.
        creditBurst?.let { (points, total) ->
            if (current.totalPoints >= total) { creditBurst = null; view.playCredit(points) }
        }
        view.setHint(hint(current))
        view.setInfoLine(infoLine(current))
        view.setParkedActions(parkedActions())
        view.setReduceMotion(reduceMotion())
        val label = when (current.vehicleState) {
            VehicleState.STOPPED_PARKED -> VehicleLabel.PARKED
            VehicleState.STOPPED_WAITING -> VehicleLabel.WAITING
            VehicleState.DRIVING -> VehicleLabel.DRIVING
            VehicleState.UNKNOWN -> VehicleLabel.CHECKING
        }
        val needsPermission = !BuildConfig.DEMO_SIGNALS && !host.speedPermissionGranted
        if (needsPermission) view.setLock("차량 속도 권한이 필요해요", "허용하면 정차했을 때 브리핑으로 크레딧을 받을 수 있어요")
        else view.setLock("차량 상태를 확인하고 있어요", "확인되면 바로 이어서 보여 드려요")
        view.showLockAction(needsPermission)
        view.setVehicle(label, menusAllowed(current), adProgress != null)
        showBriefing(adProgress)
        publishScene()
    }

    /**
     * The theater for [progress]. The ticket being played is the one after every credited briefing, so the
     * rotation index is the ledger size, read from the session itself: right after a completion AdPlayer
     * already reports the next ticket while the posted snapshot still holds the old count, and the
     * finished creative must not flash (or reload its video) for that one turn.
     */
    private fun showBriefing(progress: AdPlayer.Progress?) {
        val credited = host.ui.state.creditedCount
        view.setBriefing(progress, BriefingCatalog.forTicket(credited), BriefingCatalog.isReplay(credited))
    }

    /**
     * One status sentence (spec 5.5: one message, three lines at most, "~해요"). Parked with no ticket:
     * before any ticket today only the earning rule, after some the "done for now" lead-in, and at the
     * daily cap no promise of more today. Reaching the cap while driving needs no hint: the meter's
     * gauge line already reads "오늘 시청권을 모두 받았어요".
     */
    private fun hint(current: PlaybackState): String? {
        val tickets = current.remainingAdCount
        val vehicle = current.vehicleState
        val issuedToday = current.trip.issuedToday
        val capped = issuedToday >= current.trip.dailyCap
        val arrivedAt = naviInfo.arrivedAtMs
        val recentlyArrived = arrivedAt != null && SystemClock.elapsedRealtime() - arrivedAt in 0..ARRIVAL_HINT_MS
        if (recentlyArrived) scheduleArrivalRefresh(arrivedAt!!) else { arrivalRefresh?.let(main::removeCallbacks); arrivalRefresh = null }
        return when {
            revealing || welcoming || bonusing || adProgress != null -> null
            recentlyArrived && tickets > 0 && vehicle != VehicleState.STOPPED_PARKED -> "도착했어요. 주차하면 받을 수 있어요"
            vehicle == VehicleState.STOPPED_WAITING && tickets > 0 -> "주차하면 받을 수 있어요"
            vehicle != VehicleState.STOPPED_PARKED || tickets > 0 -> null
            capped -> "오늘 브리핑은 여기까지예요. 시청권은 내일 다시 쌓여요"
            issuedToday > 0 -> "오늘 브리핑은 여기까지예요. 달리면 1 km마다 시청권이 쌓여요"
            else -> "달리면 1 km마다 시청권이 쌓여요"
        }
    }

    /** The arrival hint expires on its own after [ARRIVAL_HINT_MS]; re-render once at that moment. */
    private fun scheduleArrivalRefresh(arrivedAt: Long) {
        if (arrivalRefresh != null) return
        val refresh = Runnable { arrivalRefresh = null; render() }
        arrivalRefresh = refresh
        main.postDelayed(refresh, (arrivedAt + ARRIVAL_HINT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 50)
    }

    /**
     * Context line from the Pleos SDKs, display only. While the navigation app guides: distance and time
     * left plus the tickets that distance would earn under today's cap. Otherwise the vehicle's odometer
     * and battery when the SDK delivered them. Null (hidden) when neither is known.
     */
    private fun infoLine(current: PlaybackState): String? {
        val remain = naviInfo.remainMeters
        if (naviInfo.guiding && remain != null) {
            val expected = minOf(remain / 1_000, (current.trip.dailyCap - current.trip.issuedToday).coerceAtLeast(0))
            val parts = mutableListOf("도착까지 %.1f km".format(remain / 1000.0))
            naviInfo.remainSeconds?.let { parts += "약 ${((it + 59) / 60).coerceAtLeast(1)}분" }
            parts += "시청권 +$expected 예상"
            return parts.joinToString(" · ")
        }
        val odometerKm = vehicleInfo.odometerKm
        val soc = vehicleInfo.socPercent
        if (odometerKm == null && soc == null) return null
        return listOfNotNull(
            odometerKm?.let { "차량 누적 %,d km".format(it.toDouble().roundToLong()) },
            soc?.let { "배터리 $it%" },
        ).joinToString(" · ")
    }

    /**
     * Buttons for the parked panel: the navigation actions only (the top bar's credits chip already opens
     * the credits page). Empty without the Pleos navigation SDK; the view hides them unless parked actions
     * are allowed.
     */
    private fun parkedActions(): List<Pair<String, () -> Unit>> {
        val nav = navi ?: return emptyList()
        if (!naviInfo.connected) return emptyList()
        return listOf(
            "목적지 검색" to { nav.openSearch("") },
            "최근 목적지로 안내" to { nav.routeToRecent { ok -> if (!ok) view.showToast("최근 목적지를 찾지 못했어요") } },
        )
    }

    private fun onNaviInfo(info: NaviSnapshot) {
        naviInfo = info
        render()
    }

    /**
     * Odometer cross-check, log only (tag CashDrivePleos, values only): each new odometer reading is
     * compared with the metres the speed meter accepted since the previous reading. Tickets never come
     * from this; the two sources are never summed.
     */
    private fun onVehicleInfo(info: PleosVehicleSnapshot) {
        vehicleInfo = info
        val km = info.odometerKm
        val today = state?.trip?.todayMeters
        if (km != null && today != null && km != lastOdometerKm) {
            lastOdometerKm = km
            val moved = (today - (crossCheckBaseMeters ?: today)).coerceAtLeast(0)
            crossCheckBaseMeters = today
            val (next, accepted) = crossCheck.accept(odometer, km, moved)
            odometer = next
            if (BuildConfig.DEBUG) Log.i(PLEOS_TAG, "crosscheck odometerKm=$km movedM=$moved acceptedM=$accepted held=${next.heldDeltas}")
        }
        render()
    }

    private fun applyLook() {
        view.setTokens(if (night) PleosTokens.dark else PleosTokens.light)
        window.decorView.setBackgroundColor(if (night) PleosTokens.dark.background else PleosTokens.light.background)
        // A page is built with the tokens of the moment it opened: the view rebuilds an open one for the new look.
        if (view.settingsOpen) view.openSettings(motionWhileDriving(), reduceMotion(), skin())
        else if (view.menuOpen) state?.let(::openCreditsPage)
        else if (view.aboutOpen) view.openAbout()
        render()
    }

    private fun menusAllowed(current: PlaybackState) = current.storageAvailable && current.vehicle.parkedActionsAllowed

    /**
     * How much the meter panel may move (design §5, §13-12): everything in park, half length and no glow
     * or count-up while driving with the motion setting on, nothing when that setting is off, at a light
     * (the scene stands there too), while the state is unknown or with reduce motion.
     */
    private fun motionLevel(current: PlaybackState): MotionLevel =
        MotionLevel.of(current.vehicleState, motionWhileDriving(), reduceMotion())

    /**
     * Scene request. Motion: reveal while the reward card is up, run while driving with the setting on,
     * stand otherwise (waiting, parked, unknown). Frames are processed (PREVIEW) only when the scene is on
     * screen, motion is not stand, reduce motion is off and no page covers the scene. The request also
     * carries the minute of the day (dusk palette), the metres to the next ticket and whether there is one
     * today ([SceneFields]), so the milestone passes the horse when the meter issues the ticket. Identical
     * requests are not republished; while driving the distance changes every second, and the renderer
     * re-anchors the milestone on each one. The scene stays on screen under the opening theater until the
     * theater is opaque ([HomeView.theaterCovering]), so the still never shows through its fade.
     */
    private fun publishScene() {
        val current = state
        val vehicle = current?.vehicleState ?: VehicleState.UNKNOWN
        val sceneShown = resumed && vehicle != VehicleState.UNKNOWN && !view.theaterCovering
        val motion = when {
            revealing -> "reveal"
            vehicle == VehicleState.DRIVING && motionWhileDriving() -> "run"
            else -> "stand"
        }
        val covered = view.pageOpen
        val preview = sceneShown && !reduceMotion() && motion != "stand" && !covered
        val frame = JSONObject()
            .put("skinId", skin())
            .put("mode", if (preview) "PREVIEW" else "STATIC")
            .put("motion", motion)
            .put("theme", if (night) "night" else "day")
            .put("reducedMotion", reduceMotion())
            .put("speedKph", current?.speedKph ?: 0.0)
            .put("minuteOfDay", SceneFields.minuteOfDay(LocalTime.now()))
            .put("metersToNext", SceneFields.metersToNext(current?.trip))
            .put("milestone", SceneFields.milestone(current?.trip))
            .put("viewport", JSONObject().put("width", maxOf(1, view.sceneHost.width)).put("height", maxOf(1, view.sceneHost.height)))
        val key = frame.toString() + preview
        if (key != lastSceneKey) {
            lastSceneKey = key
            generation += 1
            plugin?.publish(frame.put("generationId", generation).toString(), preview)
        }
        // The welcome card holds the stage for 0.8 s right before the first playback, usually while the engine
        // starts: a scene not on screen yet stays off as during playback, so the engine's first surface is not
        // created and torn down again within the launch's surface sync. A scene already on screen stays.
        val sceneLive = sceneShown && !(welcoming && view.sceneHost.visibility != View.VISIBLE)
        if (loopStarted) fragment?.let { godotHost ->
            if (sceneLive && enginePaused) { godotHost.godot?.onResume(godotHost); enginePaused = false }
            else if (!sceneLive && !enginePaused) { godotHost.godot?.onPause(godotHost); enginePaused = true }
        }
        view.setSceneLive(loopStarted && sceneLive)
    }

    private fun attachAfterDraw() {
        if (fragment != null || attaching) return
        attaching = true
        view.sceneHost.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.sceneHost.viewTreeObserver.removeOnPreDrawListener(this)
                // Create the surface after the window has drawn once (Pleos 14 relayout timeout).
                view.sceneHost.post {
                    attaching = false
                    if (!resumed || isFinishing || supportFragmentManager.isStateSaved) return@post
                    if (!engineClaimed.compareAndSet(false, true)) return@post
                    fragment = GodotFragment()
                    supportFragmentManager.beginTransaction().replace(view.sceneHost.id, fragment!!).commitNow()
                }
                return true
            }
        })
        view.sceneHost.invalidate()
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        if (resumed && autoplay.playing) { lastTick = SystemClock.uptimeMillis(); main.postDelayed(ticker, TICK_MS) }
    }

    private fun scheduleMinuteTick() {
        main.removeCallbacks(minuteTick)
        if (!resumed) return
        val now = LocalTime.now()
        main.postDelayed(minuteTick, (60 - now.second) * 1_000L - now.nano / 1_000_000 + 50)
    }

    // AdPlayer.Listener
    override fun onProgress(progress: AdPlayer.Progress?) {
        val changed = (progress == null) != (adProgress == null)
        adProgress = progress
        if (changed) render() else showBriefing(progress)
    }

    /**
     * A briefing completed (design §7): its time goes into the credits log (the ticket that finished is
     * still the one on screen; AdPlayer reports the next one after this call) and the toast says so. The
     * six stub glyphs rise around the meter's credits figure with its count-up, which starts when the posted
     * state reaches the meter ([render]); park with motion only.
     */
    override fun onCredited(points: Int, remaining: Int) {
        adProgress?.adId?.let { creditsLog.record(it, System.currentTimeMillis()) }
        // The theater is still up here: HomeView shows the toast once it closes, or drops it when the next
        // briefing follows straight on (the meter's count-up says it then).
        view.showToast("완주! $points 크레딧이 쌓였어요", reward = true)
        creditBurst = points to host.ui.state.totalPoints
    }

    /** Playback stopped mid-briefing because the session no longer allows it; the toast says why ([InterruptNotice]). */
    override fun onInterrupted(positionMs: Long) {
        view.showToast(InterruptNotice.text(state?.vehicleState ?: VehicleState.UNKNOWN, positionMs))
    }

    // HomeView.Actions
    override fun onCredits() {
        val current = state ?: return
        if (!menusAllowed(current)) return
        openCreditsPage(current)
        autoplay.hold(true); publishScene()
    }

    override fun onSettings() {
        if (state?.let(::menusAllowed) != true) return
        view.openSettings(motionWhileDriving(), reduceMotion(), skin())
        autoplay.hold(true); publishScene()
    }

    override fun onAbout() {
        if (state?.let(::menusAllowed) != true) return
        view.openAbout()
        autoplay.hold(true); publishScene()
    }

    override fun onCloseOverlay() {
        view.closeOverlay()
        // The welcome card waits for a page to close; it comes before the player is released.
        stageWaiting = false
        maybeWelcome(host.ui.state)
        maybeBonus(host.ui.state)
        holdPlayer()
        startTicker()
        render()
    }

    override fun onMotionWhileDrivingToggle() {
        prefs.edit().putBoolean(PREF_MOTION, !motionWhileDriving()).apply()
        if (view.settingsOpen) view.openSettings(motionWhileDriving(), reduceMotion(), skin())
        state?.let { view.setMotionLevel(motionLevel(it)) }
        publishScene()
    }

    override fun onReduceMotionToggle() {
        prefs.edit().putBoolean(PREF_REDUCE_MOTION, !prefs.getBoolean(PREF_REDUCE_MOTION, false)).apply()
        // The new level first: turning reduce motion on lands this very switch at once, like everything after it.
        state?.let { view.setMotionLevel(motionLevel(it)) }
        if (view.settingsOpen) view.openSettings(motionWhileDriving(), reduceMotion(), skin())
        publishScene()
    }

    /**
     * The theme is the session's (it persists with the account and changes only in park: the session refuses
     * it otherwise). The stills, the page and the scene request follow what the session now holds.
     */
    override fun onThemeSelected(id: String) {
        if (id == skin()) return
        host.ui.selectTheme(id)
        view.setSkin(skin())
        if (view.settingsOpen) view.openSettings(motionWhileDriving(), reduceMotion(), skin())
        publishScene()
    }

    /** The scene's skin: the session's theme (core ThemeIds: horse, mine, mining), the horse before the session opens. */
    private fun skin(): String = ThemeIds.normalize(if (::host.isInitialized) host.ui.state.themeId else null)

    /** Stores the preference only; no ledger change (there is nothing to deduct before a settlement). */
    override fun onRedemptionSelected(id: String) {
        if (CreditsFormat.REDEMPTIONS.none { it.id == id }) return
        prefs.edit().putString(PREF_REDEMPTION, id).apply()
        val current = state ?: return
        if (view.menuOpen) openCreditsPage(current)
    }

    override fun demoTools(): List<Pair<String, () -> Unit>> =
        if (BuildConfig.DEBUG && BuildConfig.DEMO_SIGNALS) demoToolList() else emptyList()

    /**
     * The credits page from the ledger (balance, one history row per credited briefing in rotation order),
     * the credits log (today's points, each row's time) and the stored preference. Opened again while it
     * is open, the view only moves the selection.
     */
    private fun openCreditsPage(current: PlaybackState) {
        val log = creditsLog.entries()
        view.openCredits(current.totalPoints, creditsLog.todayPoints(today()),
            CreditsFormat.history(current.creditedCount, log), current.creditedCount, redemption(),
            CreditsFormat.week(log), CreditsFormat.monthPoints(log))
    }

    private fun motionWhileDriving() = prefs.getBoolean(PREF_MOTION, true)
    private fun reduceMotion() = prefs.getBoolean(PREF_REDUCE_MOTION, false) || !ValueAnimator.areAnimatorsEnabled()
    private fun redemption() = CreditsFormat.redemptionOrDefault(prefs.getString(PREF_REDEMPTION, null))

    /**
     * Demo flavor only: synthetic vehicle states and a synthetic drive for presentations. Rejected by the
     * host in emu/vehicle/showcase builds. These are fixtures, never vehicle measurements.
     */
    private fun demoToolList(): List<Pair<String, () -> Unit>> {
        fun status(state: VehicleState, speed: Double, restrictions: Int, requiresDo: Boolean) =
            VehicleStatus(state, speed, connected = true, speedPermissionGranted = true,
                activeRestrictions = restrictions, requiresDistractionOptimization = requiresDo)
        val parked = status(VehicleState.STOPPED_PARKED, 0.0, 0, false)
        return listOf(
            "주차" to { host.updateMockVehicle(parked) },
            "신호 대기" to { host.updateMockVehicle(status(VehicleState.STOPPED_WAITING, 0.0, WAITING_RESTRICTIONS, true)) },
            "주행 40 km/h" to { host.updateMockVehicle(status(VehicleState.DRIVING, 40.0, DRIVING_RESTRICTIONS, true)) },
            "주행 5 km 적립" to { injectDemoDrive(DEMO_DRIVE_KM) },
            "충전 케이블 연결" to { host.updateMockVehicle(parked.copy(chargePortConnected = true)) },
            "상태 미확인" to { host.updateMockVehicle(VehicleStatus()) },
        )
    }

    /**
     * Feeds the synthetic drive one kilometre per main-loop turn so the panel keeps up between chunks.
     * Only while the mock vehicle is driving: distance in park or at a light would contradict the scene.
     */
    private fun injectDemoDrive(remainingKm: Int) {
        if (remainingKm <= 0) return
        if (state?.vehicleState != VehicleState.DRIVING) {
            if (remainingKm == DEMO_DRIVE_KM) view.showToast("먼저 '주행 40 km/h'로 바꿔 주세요")
            return
        }
        host.injectMockDrive(1_000)
        main.post { injectDemoDrive(remainingKm - 1) }
    }

    private fun showDemoTools() {
        val tools = demoToolList()
        AlertDialog.Builder(this).setTitle("테스트 도구").setItems(tools.map { it.first }.toTypedArray()) { _, which ->
            tools[which].second()
        }.show()
    }

    // GodotHost
    override fun getActivity(): Activity = this
    override fun getGodot(): Godot? = fragment?.godot
    /**
     * Engine arguments are fixed. Only debug builds accept the diagnosis extra
     * (`--es godotArgs "--verbose res://diag/d1_mesh.tscn"`), and only from an allowlist: this activity is
     * exported as the launcher, so a shipped build must never let another app choose engine flags.
     */
    override fun getCommandLine(): List<String> {
        val fixed = listOf("--main-pack", "res://cash_drive.pck", "--rendering-method", "gl_compatibility")
        if (!BuildConfig.DEBUG) return fixed
        val extra = intent?.getStringExtra("godotArgs")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        return fixed + extra.filter { it == "--verbose" || DIAG_SCENE.matches(it) }
    }
    override fun getHostPlugins(godot: Godot): Set<GodotPlugin> = setOf(PreviewPlugin(godot).also { plugin = it; lastSceneKey = null })
    override fun onGodotMainLoopStarted() {
        runOnUiThread {
            loopStarted = true; enginePaused = false; lastSceneKey = null
            main.removeCallbacks(stageTimeout)
            publishScene()
            // A sequence that waited for the engine starts now, over the live scene.
            if (stageWaiting) onState(host.ui.state)
        }
    }
    override fun onGodotForceQuit(instance: Godot) { runOnUiThread { finish() } }
    override fun onGodotRestartRequested(instance: Godot) { runOnUiThread { finish() } }

    private companion object {
        val DIAG_SCENE = Regex("^res://diag/[a-z0-9_]+\\.tscn$")
        const val ACCOUNT = "demo-home"
        /** AAOS baseline driving restrictions (UX_RESTRICTIONS_FULLY_RESTRICTED). */
        const val DRIVING_RESTRICTIONS = 0x1FF
        /** A waiting stop with NO_VIDEO (0x10) and NO_SETUP (0x40) still active: the P-only gate stays closed. */
        const val WAITING_RESTRICTIONS = 0x50
        const val TICK_MS = 100L
        /** How long a due reward sequence waits for the engine on a cold start before it plays without it. */
        const val STAGE_WAIT_MS = 4_000L
        const val ARRIVAL_HINT_MS = 60_000L
        const val DEMO_DRIVE_KM = 5
        const val PLEOS_TAG = "CashDrivePleos"
        const val PREF_MOTION = "motionWhileDriving"
        const val PREF_REDUCE_MOTION = "reduceMotion"
        const val PREF_REDEMPTION = "redemption"
        const val PREF_WELCOMED = "welcomeCardShown"
        const val PREF_TRIP_MARK = "tripMark"
        val engineClaimed = AtomicBoolean(false)
    }
}

/**
 * The toast when a briefing stops because playback is no longer allowed (review 2026-11-09), by what the
 * session now reports: driving off, leaving P at 0 km/h (a light, D held), or anything else (a lost or
 * unconfirmed vehicle state, a restriction in park). It never claims a departure that did not happen.
 */
object InterruptNotice {
    fun text(vehicle: VehicleState, positionMs: Long): String {
        val at = "0:%02d".format(positionMs.coerceAtLeast(0) / 1000)
        return when (vehicle) {
            VehicleState.DRIVING -> "출발해서 브리핑을 멈췄어요. 다음에 주차하면 ${at}부터 이어서 봐요"
            VehicleState.STOPPED_WAITING -> "P단에서 벗어나 브리핑을 멈췄어요. 다시 주차하면 ${at}부터 이어서 봐요"
            else -> "차량 상태를 확인하는 동안 브리핑을 멈췄어요. 주차가 확인되면 ${at}부터 이어서 봐요"
        }
    }
}
