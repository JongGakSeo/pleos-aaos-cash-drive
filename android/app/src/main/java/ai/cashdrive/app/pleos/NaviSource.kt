package ai.cashdrive.app.pleos

import ai.cashdrive.app.BuildConfig
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * What the home reads from the navigation app. Every value comes from Pleos NaviHelper events and is used for
 * hint text only; the safety gate and the ledger read AAOS signals alone.
 *
 * - [connected]: the NaviHelper service binding is up.
 * - [guiding]: a route is active as far as NaviHelper has said (route started, routing completed, or a
 *   destination with remaining distance). Cleared by onRouteCancelled and onDestinationArrived.
 * - [remainMeters] / [remainSeconds]: the latest DestinationInfo while guiding, null otherwise, so
 *   `remainMeters != null` implies [guiding].
 * - [arrivedAtMs]: `SystemClock.elapsedRealtime()` of the last onDestinationArrived; null again when a new
 *   route starts.
 */
data class NaviSnapshot(
    val connected: Boolean = false,
    val guiding: Boolean = false,
    val destinationName: String? = null,
    val remainMeters: Int? = null,
    val remainSeconds: Int? = null,
    val arrivedAtMs: Long? = null,
)

/**
 * Read-only adapter over Pleos NaviHelper 3.0.5 (permissions NAVI_ROUTE, NAVI_ROUTE_SEARCH, NAVI_CUSTOM_MAP,
 * all normal and declared in the main manifest).
 *
 * Threading: SDK callbacks arrive on binder or coroutine threads; every one of them is re-posted to the main
 * thread before it touches state, and [onSnapshot] is always called on the main thread. Public methods may be
 * called from any thread.
 *
 * Connection: `initialize(onConnected, onFailed)` lets the SDK retry the binding itself (30 x 500 ms). When
 * that reports failure this adapter waits 30 s and tries once more, then stays quiet (disconnected snapshot)
 * until the next [stop] / [start] cycle. Nothing here is fatal for the app.
 *
 * Logging: tag `CashDriveNavi`, values and name lengths only, never coordinates, addresses or place names.
 */
class NaviSource(context: Context, private val onSnapshot: (NaviSnapshot) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var active = false
    private var closed = false
    private var generation = 0L
    private var snapshot = NaviSnapshot()
    private var lastPublished: NaviSnapshot? = null
    private var pendingRecent: ((Boolean) -> Unit)? = null
    private var pendingRecentTimeout: Runnable? = null

    fun start() = onMain {
        if (active || closed) return@onMain
        active = true
        publish(NaviSnapshot())
        connect(++generation, retried = false)
    }

    fun stop() = onMain {
        active = false
        generation++
        main.removeCallbacksAndMessages(null)
        takePendingRecent()?.invoke(false)
        release()
        publish(NaviSnapshot())
    }

    override fun close() = onMain {
        if (closed) return@onMain
        stop()
        closed = true
    }

    /**
     * Opens the navigation app's place search screen (`requestNavigationScreen(SearchPlace)`). The UI calls
     * this only while parked; without a connection the call is dropped quietly.
     */
    fun openSearch(keyword: String = "") = onMain {
        val sdk = helper
        if (sdk == null || !snapshot.connected) {
            if (BuildConfig.DEBUG) Log.i(TAG, "openSearch ignored: not connected")
            return@onMain
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "openSearch keywordLen=${keyword.length}")
        request("requestNavigationScreen") { sdk.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }
    }

    /**
     * Asks for the recent destinations and requests a route to the first one. [onResult] runs on the main
     * thread: true when the route request was handed to the navigation app, false when there is no connection,
     * the list is empty, a call failed, or no list arrived within [RECENT_TIMEOUT_MS]. Route progress then
     * flows through the snapshot (onRouteStarted, onDestinationInfo, ...).
     */
    fun routeToRecent(onResult: (Boolean) -> Unit) = onMain {
        val sdk = helper
        if (sdk == null || !snapshot.connected) {
            if (BuildConfig.DEBUG) Log.i(TAG, "routeToRecent ignored: not connected")
            onResult(false)
            return@onMain
        }
        takePendingRecent()?.invoke(false)
        pendingRecent = onResult
        val timeout = Runnable {
            takePendingRecent()?.let { callback ->
                if (BuildConfig.DEBUG) Log.i(TAG, "recentDestinations timeout")
                callback(false)
            }
        }
        pendingRecentTimeout = timeout
        main.postDelayed(timeout, RECENT_TIMEOUT_MS)
        if (!request("getRecentDestinationInfo") { sdk.getRecentDestinationInfo() }) takePendingRecent()?.invoke(false)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private fun current(expected: Long) = active && !closed && generation == expected

    private fun connect(expected: Long, retried: Boolean) {
        if (!current(expected)) return
        release()
        val sdk = NaviHelper(context)
        val events = Events(expected)
        helper = sdk
        listener = events
        sdk.addListener(events)
        try {
            sdk.initialize(
                onConnected = { onMain { if (current(expected)) connected(sdk, expected) } },
                onFailed = { cause -> onMain { if (current(expected)) failed(expected, retried, cause) } },
            )
        } catch (e: RuntimeException) {
            onMain { if (current(expected)) failed(expected, retried, e) }
        }
    }

    private fun connected(sdk: NaviHelper, expected: Long) {
        if (BuildConfig.DEBUG) Log.i(TAG, "connected=true generation=$expected")
        update { it.copy(connected = true) }
        // Seed the remaining distance and time in case the navigation app is already guiding.
        request("getDestinationInfo") { sdk.getDestinationInfo() }
    }

    private fun failed(expected: Long, retried: Boolean, cause: Throwable) {
        Log.w(TAG, "connect failed willRetry=${!retried} cause=${cause.javaClass.simpleName}: ${cause.message}")
        release()
        publish(NaviSnapshot())
        if (retried) return
        main.postDelayed({ if (current(expected)) connect(expected, retried = true) }, RETRY_DELAY_MS)
    }

    /** Runs one SDK call; a SecurityException (permission missing) or a binder failure is logged and swallowed. */
    private fun request(name: String, call: () -> Unit): Boolean = try {
        call()
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "$name failed: ${e.javaClass.simpleName}")
        false
    }

    private fun takePendingRecent(): ((Boolean) -> Unit)? {
        pendingRecentTimeout?.let { main.removeCallbacks(it) }
        pendingRecentTimeout = null
        return pendingRecent.also { pendingRecent = null }
    }

    private fun update(transform: (NaviSnapshot) -> NaviSnapshot) = publish(transform(snapshot))

    private fun publish(next: NaviSnapshot) {
        snapshot = next
        if (lastPublished == next) return
        lastPublished = next
        if (BuildConfig.DEBUG) Log.i(TAG, "connected=${next.connected} guiding=${next.guiding} remainM=${next.remainMeters} " +
            "remainS=${next.remainSeconds} arrived=${next.arrivedAtMs != null} nameLen=${next.destinationName?.length}")
        onSnapshot(next)
    }

    private fun release() {
        val sdk = helper ?: return
        listener?.let { runCatching { sdk.removeListener(it) } }
        runCatching { sdk.release() }
        helper = null
        listener = null
    }

    /** NaviHelper events for one connection generation; stale generations are dropped on the main thread. */
    private inner class Events(private val expected: Long) : NaviHelperEventListener {
        private fun deliver(action: () -> Unit) = onMain { if (current(expected)) action() }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = deliver {
            if (BuildConfig.DEBUG) Log.i(TAG, "routeStarted distanceM=${routeStartInfo.distance} durationS=${routeStartInfo.duration} " +
                "nameLen=${routeStartInfo.name.length}")
            update {
                it.copy(
                    guiding = true,
                    destinationName = routeStartInfo.name.ifBlank { it.destinationName },
                    remainMeters = routeStartInfo.distance.coerceAtLeast(0),
                    remainSeconds = routeStartInfo.duration.coerceAtLeast(0),
                    arrivedAtMs = null,
                )
            }
            request("getDestinationInfo") { helper?.getDestinationInfo() }
        }

        // The SDK documents onRouteEnded as "route search is completed" and deprecates it in favour of
        // onRoutingCompleted, so both mean routing finished and guidance is active; guidance ends only through
        // onRouteCancelled or onDestinationArrived.
        @Deprecated("NaviHelper 3.0.5 replaces this with onRoutingCompleted; older navigation apps may still send it")
        override fun onRouteEnded() = routingCompleted("routeEnded")

        override fun onRoutingCompleted() = routingCompleted("routingCompleted")

        private fun routingCompleted(event: String) = deliver {
            if (BuildConfig.DEBUG) Log.i(TAG, event)
            update { it.copy(guiding = true, arrivedAtMs = null) }
            request("getDestinationInfo") { helper?.getDestinationInfo() }
        }

        override fun onRouteCancelled() = deliver {
            if (BuildConfig.DEBUG) Log.i(TAG, "routeCancelled")
            update { it.copy(guiding = false, destinationName = null, remainMeters = null, remainSeconds = null) }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = deliver {
            if (BuildConfig.DEBUG) Log.i(TAG, "arrived distanceM=${destinationArrivedInfo.distance} durationS=${destinationArrivedInfo.duration} " +
                "nameLen=${destinationArrivedInfo.name.length}")
            update {
                it.copy(guiding = false, remainMeters = null, remainSeconds = null, arrivedAtMs = SystemClock.elapsedRealtime())
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = deliver {
            if (BuildConfig.DEBUG) Log.i(TAG, "destinationInfo remainM=${destinationInfo.remainDistance} remainS=${destinationInfo.remainTime} " +
                "nameLen=${destinationInfo.name.length}")
            update {
                // A reply without an active destination (nothing remaining while not guiding) changes nothing.
                if (!it.guiding && destinationInfo.remainDistance <= 0) return@update it
                it.copy(
                    guiding = true,
                    destinationName = destinationInfo.name.ifBlank { it.destinationName },
                    remainMeters = destinationInfo.remainDistance.coerceAtLeast(0),
                    remainSeconds = destinationInfo.remainTime.coerceAtLeast(0),
                )
            }
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = deliver {
            if (BuildConfig.DEBUG) Log.i(TAG, "recentDestinations count=${recentDestinationInfoList.size}")
            val callback = takePendingRecent() ?: return@deliver
            val first = recentDestinationInfoList.firstOrNull()
            val sdk = helper
            if (first == null || sdk == null) {
                callback(false)
                return@deliver
            }
            callback(request("requestRoute") { sdk.requestRoute(first.toRouteInfo()) })
        }

        override fun onError(errorCode: NaviErrorCode) = deliver {
            Log.w(TAG, "naviError code=$errorCode")
        }
    }

    companion object {
        private const val TAG = "CashDriveNavi"
        private const val RETRY_DELAY_MS = 30_000L
        private const val RECENT_TIMEOUT_MS = 5_000L

        /** RecentDestinationInfo -> RouteInfo, field for field (poiSubId is nullable on the recent side only). */
        private fun RecentDestinationInfo.toRouteInfo() = RouteInfo(
            latitude = latitude,
            longitude = longitude,
            displayLatitude = displayLatitude,
            displayLongitude = displayLongitude,
            poiName = name,
            poiId = poiId,
            address = address,
            poiSubId = poiSubId ?: "",
            poiProvider = poiProvider,
        )
    }
}
