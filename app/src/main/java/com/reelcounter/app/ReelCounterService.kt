package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Counts Instagram Reels and YouTube Shorts - each [Platform] keeps its own totals - and keeps the floating
 * counter in sync.
 *
 * A reel/short counts the moment you scroll to it. Each pager position is remembered, so scrolling back up
 * and down never counts the same one twice; that memory is wiped whenever you leave the Reels/Shorts screen
 * or the app, so a fresh session is never skipped.
 *
 * Everything app-specific lives in [ids] and the constants at the bottom. Instagram and YouTube change
 * their internals from time to time, so if counting stops working, that is the place to tune.
 */
class ReelCounterService : AccessibilityService() {

    /** How to recognise a platform's vertical short-video pager. */
    private class PagerIds(
        /** Bottom-bar tab ids (without package prefix) that are "selected" while the feed is open. */
        val tabIds: List<String>,
        /** View ids that exist only inside the full-screen pager. */
        val viewerIds: List<String>,
        /** Show the popup on every screen of the app (true) or only inside the pager (false). */
        val popupEverywhere: Boolean
    )

    private fun ids(p: Platform) = when (p) {
        Platform.Reels -> PagerIds(listOf("clips_tab"), listOf("clips_viewer_view_pager"), popupEverywhere = true)
        Platform.Shorts -> PagerIds(emptyList(), listOf("reel_recycler", "reel_player_page_container"), popupEverywhere = false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: ReelOverlay
    private var tickerJob: Job? = null
    private var imePackage: String? = null

    // Which tracked app is in the foreground, and whether its Reels/Shorts pager is on screen
    private var active: Platform? = null
    private var inFeed = false
    private var feedCheckedAt = 0L

    // Counting state
    private var currentIndex = NONE
    private val countedIndices = HashSet<Int>()
    private var pseudoIndex = 0
    private var lastScrollAt = 0L
    private var lastDay = ""

    // Watch time: accumulated in memory, written to disk in batches
    private var watchMark = 0L          // uptime up to which time has been accounted; 0 = not watching
    private var watchPendingMs = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = setActive(null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.coroutineContext.cancelChildren()
        runCatching { unregisterReceiver(screenOffReceiver) }
        ReelRepository.init(this)
        AppSettings.init(this)
        overlay = ReelOverlay(this)
        imePackage = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            combine(
                Platform.Reels.store.counts, Platform.Shorts.store.counts,
                AppSettings.limit(Platform.Reels), AppSettings.limit(Platform.Shorts), AppSettings.transparency
            ) { _, _, _, _, _ -> }.collect { active?.let { overlay.refresh(it) } }
        }
        scope.launch { AppSettings.overlayEnabled.collect { syncOverlay() } }

        updateEventTypes(appOpen = false)   // scroll events only matter while a tracked app is open
        Platform.fromPackage(rootInActiveWindow?.packageName?.toString() ?: "")?.let { setActive(it) }
    }

    // ------------------------------------------------------------------ events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == active?.pkg) onScroll(event)
        }
    }

    private fun onWindowChanged(pkg: String, className: String?) {
        val src = Platform.fromPackage(pkg)
        when {
            src != null -> if (active == src) syncOverlay() else setActive(src)
            // Status bar, keyboard and our own overlay must not count as "left the app".
            pkg == "com.android.systemui" || pkg == imePackage -> Unit
            pkg == packageName && className != MainActivity::class.java.name -> Unit
            else -> setActive(null)
        }
    }

    private fun onScroll(event: AccessibilityEvent) {
        val src = active ?: return
        val now = SystemClock.uptimeMillis()
        refreshFeedState(src, now)
        if (!inFeed) return

        val realIndex = if (event.fromIndex >= 0 && event.itemCount > 0) event.fromIndex else -1

        // Cheap path: nearly every scroll event (one per few pixels of a swipe) is for the reel we
        // already track, so skip the expensive checks below.
        if (realIndex >= 0 && realIndex == currentIndex) { lastScrollAt = now; return }
        if (!looksLikePager(event.source)) return

        val index = if (realIndex >= 0) realIndex else nextGestureIndex(event, now)
        lastScrollAt = now
        if (index == currentIndex) return

        // Pager jumped back to the very top after being deep in the feed = refreshed feed.
        if (index == 0 && currentIndex >= 3) countedIndices.clear()

        // A fast flick can skip events; count the few positions it passed over as well.
        val from = if (currentIndex != NONE && index - currentIndex in 2..MAX_SKIPPED) currentIndex + 1 else index
        currentIndex = index
        var added = 0
        for (i in from..index) if (countedIndices.add(i)) added++
        if (added > 0) src.store.adjustToday(added)
    }

    /** Fallback when the app reports no pager position: one step per swipe gesture, direction if known. */
    private fun nextGestureIndex(event: AccessibilityEvent, now: Long): Int {
        if (now - lastScrollAt > GESTURE_GAP_MS) {
            val backwards = Build.VERSION.SDK_INT >= 28 && event.scrollDeltaY < 0
            pseudoIndex += if (backwards) -1 else 1
        }
        return pseudoIndex
    }

    // ------------------------------------------------------------------ state

    private fun setActive(src: Platform?) {
        if (active == src) return
        endWatch(SystemClock.uptimeMillis())
        resetTracking()
        active = src
        inFeed = false
        feedCheckedAt = 0L
        updateEventTypes(src != null)
        tickerJob?.cancel()
        tickerJob = if (src != null) scope.launch { while (isActive) { delay(TICK_MS); tick() } } else null
        syncOverlay()
    }

    private fun setInFeed(value: Boolean, now: Long) {
        if (inFeed == value) return
        inFeed = value
        if (value) watchMark = now else { endWatch(now); resetTracking() }
        syncOverlay()
    }

    /** Forget everything about the current pager session (positions counted, scroll state). */
    private fun resetTracking() {
        currentIndex = NONE
        countedIndices.clear()
        pseudoIndex = 0
    }

    /** One light check per second while a tracked app is open; the only periodic work in the service. */
    private fun tick() {
        val src = active ?: return
        val day = DayClock.today()
        if (day != lastDay) { lastDay = day; overlay.refresh(src) }   // new day: popup goes back to 0
        val now = SystemClock.uptimeMillis()
        refreshFeedState(src, now)
        if (inFeed) {
            accountWatch(now)
            if (watchPendingMs >= WATCH_FLUSH_MS) flushWatch()
        }
    }

    /** Is the Reels/Shorts pager on screen? Cached, since each lookup is an IPC round trip. */
    private fun refreshFeedState(src: Platform, now: Long) {
        if (now - feedCheckedAt < FEED_CACHE_MS) return
        feedCheckedAt = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != src.pkg) return
        val pager = ids(src)
        val found = pager.tabIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").any { it.isSelected }
        } || pager.viewerIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").isNotEmpty()
        }
        setInFeed(found, now)
    }

    /** Ignores comment lists, carousels, etc. - the pager fills most of the screen. */
    private fun looksLikePager(node: AccessibilityNodeInfo?): Boolean {
        node ?: return true
        val r = Rect()
        node.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        return r.height() >= dm.heightPixels * MIN_HEIGHT_RATIO && r.width() >= dm.widthPixels * MIN_WIDTH_RATIO
    }

    // ------------------------------------------------------------------ watch time

    private fun accountWatch(now: Long) {
        if (watchMark == 0L) return
        val d = now - watchMark
        watchMark = now
        if (d in 1..MAX_WATCH_GAP_MS) watchPendingMs += d   // ignore long gaps (device hiccups)
    }

    private fun flushWatch() {
        if (watchPendingMs <= 0L) return
        active?.store?.addWatchMs(watchPendingMs)
        watchPendingMs = 0L
    }

    /** Left the pager (or the app, or the service stopped): account the last stretch and save. */
    private fun endWatch(now: Long) {
        if (watchMark != 0L) { accountWatch(now); watchMark = 0L }
        flushWatch()
    }

    // ------------------------------------------------------------------ overlay & plumbing

    private fun syncOverlay() {
        val src = active
        if (src != null && AppSettings.overlayEnabled.value && (ids(src).popupEverywhere || inFeed)) overlay.show(src)
        else overlay.hide()
    }

    /** Other apps' scroll events are pure overhead, so only subscribe to them while a tracked app is open. */
    private fun updateEventTypes(appOpen: Boolean) {
        val info = serviceInfo ?: return
        val wanted = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (appOpen) AccessibilityEvent.TYPE_VIEW_SCROLLED else 0)
        if (info.eventTypes != wanted) {
            info.eventTypes = wanted
            serviceInfo = info
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (::overlay.isInitialized) overlay.hide()
        endWatch(SystemClock.uptimeMillis())
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        endWatch(SystemClock.uptimeMillis())
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val FEED_CACHE_MS = 900L
        private const val MAX_WATCH_GAP_MS = 3000L
        private const val WATCH_FLUSH_MS = 15_000L
        private const val MAX_SKIPPED = 4
        private const val MIN_HEIGHT_RATIO = 0.6f
        private const val MIN_WIDTH_RATIO = 0.8f
        private const val GESTURE_GAP_MS = 700L
        private const val NONE = Int.MIN_VALUE
    }
}
