package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Counts Instagram Reels and YouTube Shorts into one merged total (1 short = 1 reel) and keeps the floating
 * counter in sync.
 *
 * A reel/short counts once you have stayed on it for the configured number of seconds. Each pager position is
 * remembered, so scrolling back up and down never counts the same one twice; that memory is wiped whenever you
 * leave the Reels/Shorts screen or the app, so a fresh session is never skipped.
 *
 * An app switched off in the settings is ignored completely: no counting, no popup, no scroll events.
 *
 * Built to run 24/7:
 *  - With no tracked app open there is no timer, no scroll subscription and no tree access at all.
 *  - With one open, a single ticker sleeps as long as it can and is woken by events instead of polling.
 *  - Tree reads are bounded, cached and only happen when something moved (see [PageScanner]).
 *
 * Everything app-specific lives in [ids] and the constants at the bottom. Instagram and YouTube change
 * their internals from time to time, so if counting stops working, that is the place to tune.
 */
class ReelCounterService : AccessibilityService() {

    /** How to recognise an app's vertical short-video pager. */
    private class PagerIds(
        /** Bottom-bar tab ids (without package prefix) that are "selected" while the feed is open. */
        val tabIds: List<String>,
        /** View ids that exist only inside the full-screen pager. */
        val viewerIds: List<String>,
        /** Show the popup on every screen of the app (true) or only inside the pager (false). */
        val popupEverywhere: Boolean,
        /**
         * false: identify the reel by the pager position that scroll events report (Instagram).
         * true: identify it by the readable text on the page in the middle of the screen, read lightly
         * by the ticker - scroll events from this pager are unreliable (Shorts).
         */
        val readsPageKey: Boolean,
        /**
         * true: also count reels/videos that autoplay in the app's ordinary home feed (Instagram's FYP),
         * not only inside the dedicated Reels pager.
         */
        val homeFeed: Boolean
    )

    private fun ids(p: Platform) = when (p) {
        Platform.Reels -> PagerIds(
            listOf("clips_tab"), listOf("clips_viewer_view_pager"),
            popupEverywhere = true, readsPageKey = false, homeFeed = true
        )
        Platform.Shorts -> PagerIds(
            emptyList(), listOf("reel_recycler", "reel_player_page_container", "reel_watch_fragment_root"),
            popupEverywhere = false, readsPageKey = true, homeFeed = false
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: ReelOverlay
    private lateinit var scanner: PageScanner
    private var tickerJob: Job? = null
    private var foregroundCheckJob: Job? = null
    private var imePackage: String? = null

    /** Lets events cut the ticker's sleep short, so it can sleep long while nothing is happening. */
    private val wakeTicker = Channel<Unit>(Channel.CONFLATED)

    // Which tracked app is in the foreground, and whether its Reels/Shorts pager is on screen
    private var active: Platform? = null
    private var inFeed = false
    private var feedCheckedAt = 0L
    private var fallbackCheckedAt = 0L
    private var feedMisses = 0           // consecutive "pager not found" checks; leaving needs several in a row
    private var lastPollAt = 0L
    private var lastSeenScrollAt = 0L    // any scroll in the tracked app, even before a feed is detected

    // Instagram home feed: reels inside the ordinary feed, read while the user is scrolling it
    private var inHome = false
    private var homeNeedsRead = false

    // The reel/short currently on screen
    private var currentIndex = NONE
    private var currentSince = 0L
    private var currentCounted = false
    private val countedIndices = HashSet<Int>()
    private var pseudoIndex = 0
    private var lastScrollAt = 0L        // last scroll that reached the counting logic (gesture detection)
    private var lastDay = ""

    // Watch time: accumulated in memory, written to disk in batches
    private var watchMark = 0L           // uptime up to which time has been accounted; 0 = not watching
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
        scanner = PageScanner(resources)
        imePackage = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            combine(ReelRepository.store.counts, AppSettings.limit, AppSettings.transparency) { _, _, _ -> }
                .collect { overlay.refresh() }
        }
        scope.launch {
            combine(
                AppSettings.overlayEnabled, AppSettings.tracking(Platform.Reels), AppSettings.tracking(Platform.Shorts)
            ) { _, _, _ -> }.collect { onSettingsChanged() }
        }

        TrackingStatus.update(TrackingState.IDLE, null)   // never show state left over from a previous run
        updateEventTypes(appOpen = false)   // scroll events only matter while a tracked app is open
        trackedPlatform(rootInActiveWindow?.packageName?.toString())?.let { setActive(it) }
    }

    // ------------------------------------------------------------------ events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == active?.pkg) onScroll(event)
        }
    }

    /** The platform for this package, but only if its switch is on. */
    private fun trackedPlatform(pkg: String?): Platform? =
        pkg?.let { Platform.fromPackage(it) }?.takeIf { AppSettings.tracking(it).value }

    private fun onWindowChanged(pkg: String, className: String?) {
        val src = trackedPlatform(pkg)
        when {
            src != null -> {
                foregroundCheckJob?.cancel()
                // A fresh window in the same app can mean the accessibility tree was rebuilt.
                // Force the next tick to re-check the feed instead of trusting cached state.
                feedCheckedAt = 0L
                if (active == src) {
                    updateTrackingStatus()
                    syncOverlay()
                } else setActive(src)
            }
            // Status bar, keyboard and our own accessibility overlay are transient windows.
            pkg == "com.android.systemui" || pkg == imePackage -> Unit
            pkg == packageName && className != MainActivity::class.java.name -> Unit
            else -> verifyForegroundAfterTransition()
        }
    }

    /**
     * Window-state events briefly report launchers, permission surfaces and other intermediary windows while
     * Android is switching apps. Resetting immediately can strand the service in an inactive state if the
     * tracked app becomes foreground again without another useful event. Verify the settled active root first.
     */
    private fun verifyForegroundAfterTransition() {
        val current = active ?: return
        TrackingStatus.update(TrackingState.TRANSITIONING, current)
        foregroundCheckJob?.cancel()
        foregroundCheckJob = scope.launch {
            delay(FOREGROUND_SETTLE_MS)
            repeat(FOREGROUND_CHECKS) { attempt ->
                val rootPkg = rootInActiveWindow?.packageName?.toString()
                val settled = trackedPlatform(rootPkg)
                when {
                    settled != null -> {
                        feedCheckedAt = 0L
                        if (active != settled) setActive(settled) else {
                            updateTrackingStatus()
                            syncOverlay()
                        }
                        return@launch
                    }
                    rootPkg == null || rootPkg == "com.android.systemui" || rootPkg == imePackage -> {
                        if (attempt + 1 < FOREGROUND_CHECKS) delay(FOREGROUND_RETRY_MS)
                    }
                    else -> {
                        setActive(null)
                        return@launch
                    }
                }
            }
            // If Android never exposed a stable root, keep the current app state. The ticker will not count
            // against a mismatched root, and a later window event will settle the state safely.
            updateTrackingStatus()
        }
    }

    /** A switch was flipped (or the popup toggled): stop everything for an app that was just turned off. */
    private fun onSettingsChanged() {
        val a = active
        if (a != null && !AppSettings.tracking(a).value) setActive(null) else syncOverlay()
    }

    private fun onScroll(event: AccessibilityEvent) {
        val src = active ?: return
        val now = SystemClock.uptimeMillis()
        val burstStart = now - lastSeenScrollAt > BURST_GAP_MS
        lastSeenScrollAt = now
        refreshFeedState(src, now)
        val pager = ids(src)

        if (!inFeed) {
            // Instagram home feed: just note that something moved; the ticker reads the page once it settles.
            if (pager.homeFeed) {
                homeNeedsRead = true
                if (!inHome || burstStart) wake()
            }
            return
        }
        if (pager.readsPageKey) {
            // Shorts: scroll events only move the ticker into its faster mode; the page is read by tick().
            if (burstStart) wake()
            return
        }

        val realIndex = if (event.fromIndex >= 0 && event.itemCount > 0) event.fromIndex else -1

        // Cheap path: nearly every scroll event (one per few pixels of a swipe) is for the reel we
        // already track, so skip the expensive checks below.
        if (realIndex >= 0 && realIndex == currentIndex) { lastScrollAt = now; return }
        if (!scanner.looksLikePager(event.source)) return

        val index = if (realIndex >= 0) realIndex else nextGestureIndex(event, now)
        lastScrollAt = now
        if (index == currentIndex) return

        if (currentIndex == UNKNOWN && realIndex >= 0) {
            // First scroll after entering: the reel we have been timing all along is `index`.
            currentIndex = index
            if (currentCounted) markCounted(index) else if (index in countedIndices) currentCounted = true
        } else {
            countIfDue(now)   // the reel being left may just have reached the threshold
            // Pager jumped back to the very top after being deep in the feed = refreshed feed.
            if (index == 0 && currentIndex >= 3) countedIndices.clear()
            startReel(index, now)
        }
    }

    /** Fallback when the app reports no pager position: one step per swipe gesture, direction if known. */
    private fun nextGestureIndex(event: AccessibilityEvent, now: Long): Int {
        if (now - lastScrollAt > GESTURE_GAP_MS) {
            val backwards = Build.VERSION.SDK_INT >= 28 && event.scrollDeltaY < 0
            pseudoIndex += if (backwards) -1 else 1
        }
        return pseudoIndex
    }

    // ------------------------------------------------------------------ counting

    private fun startReel(index: Int, now: Long) {
        currentIndex = index
        currentSince = now
        currentCounted = index == NON_VIDEO || (index != UNKNOWN && index in countedIndices)
    }

    /** Remember an item as counted. Bounded, so a days-long session cannot grow the set without limit. */
    private fun markCounted(index: Int): Boolean {
        if (countedIndices.size >= MAX_COUNTED) countedIndices.clear()
        return countedIndices.add(index)
    }

    /** Count the reel on screen once it has been there long enough (and was not counted before). */
    private fun countIfDue(now: Long) {
        if (currentIndex == NONE || currentCounted) return
        if (currentIndex == UNKNOWN && inHome) return   // home feed: wait until the item is identified
        if (now - currentSince < AppSettings.seconds.value * 1000L) return
        currentCounted = true
        if (currentIndex == UNKNOWN || markCounted(currentIndex)) ReelRepository.store.adjustToday(1)
    }

    /** How long the ticker may sleep before the reel on screen reaches the threshold (capped at [cap]). */
    private fun dwellWake(now: Long, cap: Long): Long {
        if (currentIndex == NONE || currentCounted || (currentIndex == UNKNOWN && inHome)) return cap
        val due = currentSince + AppSettings.seconds.value * 1000L
        return (due - now).coerceIn(MIN_WAKE_MS, cap)
    }

    /** The reel/short on screen changed (or was seen for the first time): finish the old one, time the new one. */
    private fun observePage(key: Int, now: Long) {
        if (currentIndex == NONE || key == currentIndex) return
        if (currentIndex == UNKNOWN) {
            // First look after entering: this is the page we have been timing all along.
            currentIndex = key
            if (key == NON_VIDEO) currentCounted = true
            else if (currentCounted) markCounted(key) else if (key in countedIndices) currentCounted = true
        } else {
            countIfDue(now)
            startReel(key, now)
        }
    }

    private fun pollPage(src: Platform, now: Long, root: AccessibilityNodeInfo?) {
        lastPollAt = now
        if (root == null || root.packageName?.toString() != src.pkg) return
        scanner.readPagerKey(root, src.pkg, ids(src).viewerIds)?.let { observePage(it, now) }
    }

    // ------------------------------------------------------------------ home feed (Instagram FYP)

    private fun enterHome(now: Long) {
        inHome = true
        homeNeedsRead = true
        startReel(UNKNOWN, now)
        updateTrackingStatus()
    }

    /** Scrolling stopped for a while: stop reading the screen. Already counted items stay remembered. */
    private fun pauseHome(now: Long) {
        if (!inHome) return
        countIfDue(now)
        inHome = false
        homeNeedsRead = false
        currentIndex = NONE
        currentCounted = false
        scanner.reset()
        updateTrackingStatus()
    }

    /**
     * Periodic part of home-feed counting. The page is read only once the scroll has settled and only if
     * something scrolled since the last read, so resting on a reel costs nothing. Returns how long to sleep.
     */
    private fun tickHome(src: Platform, now: Long): Long {
        val sinceScroll = now - lastSeenScrollAt
        if (sinceScroll >= HOME_ACTIVE_MS) { pauseHome(now); return OUT_TICK_MS }
        if (!inHome) enterHome(now)

        var sleep = HOME_IDLE_TICK_MS
        if (homeNeedsRead) {
            if (sinceScroll >= SETTLE_MS) {
                pollHome(src, now)
                if (homeNeedsRead) sleep = POLL_MS          // could not identify it yet: retry shortly
            } else {
                sleep = (SETTLE_MS - sinceScroll).coerceAtLeast(MIN_WAKE_MS)
            }
        }
        countIfDue(now)
        return dwellWake(now, sleep)
    }

    private fun pollHome(src: Platform, now: Long) {
        lastPollAt = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != src.pkg) return
        val key = scanner.readHomeKey(root, now, lastSeenScrollAt) ?: return
        homeNeedsRead = false
        observePage(key, now)
    }

    /** Forget the current pager session. Called when leaving the pager or the app. */
    private fun resetTracking(now: Long) {
        countIfDue(now)   // a reel watched long enough right before leaving still counts
        inHome = false
        homeNeedsRead = false
        if (::scanner.isInitialized) scanner.reset()
        currentIndex = NONE
        currentCounted = false
        countedIndices.clear()
        pseudoIndex = 0
    }

    // ------------------------------------------------------------------ state

    private fun setActive(src: Platform?) {
        if (active == src) return
        val now = SystemClock.uptimeMillis()
        endWatch(now)
        resetTracking(now)
        foregroundCheckJob?.cancel()
        active = src
        inFeed = false
        feedCheckedAt = 0L
        fallbackCheckedAt = 0L
        feedMisses = 0
        lastPollAt = 0L
        updateEventTypes(src != null)
        tickerJob?.cancel()
        tickerJob = if (src != null) scope.launch {
            while (isActive) {
                val sleep = tick()
                withTimeoutOrNull(sleep) { wakeTicker.receive() }
            }
        } else null
        updateTrackingStatus()
        syncOverlay()
    }

    private fun setInFeed(value: Boolean, now: Long) {
        if (inFeed == value) return
        inFeed = value
        if (value) {
            pauseHome(now)    // the dedicated Reels pager takes over from home-feed reading
            watchMark = now
            startReel(UNKNOWN, now)   // the reel shown on entry; its position becomes known on the first scroll
            wake()
        } else {
            endWatch(now)
            resetTracking(now)
        }
        updateTrackingStatus()
        syncOverlay()
    }

    private fun wake() { wakeTicker.trySend(Unit) }

    private fun updateTrackingStatus() {
        val src = active
        TrackingStatus.update(
            when {
                src == null -> TrackingState.IDLE
                inFeed || inHome -> TrackingState.FEED_ACTIVE
                else -> TrackingState.APP_ACTIVE
            },
            src
        )
    }

    /**
     * The only periodic work, and it sleeps as long as it can:
     *  - not in a feed: every [OUT_TICK_MS] (events wake it sooner when scrolling starts);
     *  - Instagram Reels tab: once a second (watch time and the seconds rule);
     *  - Shorts: a light page read every [POLL_MS] while scrolling, [IDLE_POLL_MS] when resting;
     *  - a precisely timed wake-up when a reel is about to reach the threshold.
     * Returns how long to sleep.
     */
    private fun tick(): Long {
        val src = active ?: return OUT_TICK_MS
        val day = DayClock.today()
        if (day != lastDay) { lastDay = day; overlay.refresh() }   // new day: popup goes back to 0
        val now = SystemClock.uptimeMillis()
        val pager = ids(src)
        // Share one root between the feed check and the Shorts page read.
        val root = if (pager.readsPageKey && inFeed) rootInActiveWindow else null
        refreshFeedState(src, now, root)
        if (!inFeed) return if (pager.homeFeed) tickHome(src, now) else OUT_TICK_MS

        val interval: Long
        if (pager.readsPageKey) {
            interval = if (now - lastSeenScrollAt < ACTIVE_SCROLL_MS) POLL_MS else IDLE_POLL_MS
            if (now - lastPollAt >= interval - POLL_SLACK_MS) pollPage(src, now, root ?: rootInActiveWindow)
        } else {
            interval = TICK_MS
        }
        accountWatch(now)
        if (watchPendingMs >= WATCH_FLUSH_MS) flushWatch()

        countIfDue(now)
        return dwellWake(now, interval)
    }

    /** Is the Reels/Shorts pager on screen? Cached, since each lookup is an IPC round trip. */
    private fun refreshFeedState(src: Platform, now: Long, preloaded: AccessibilityNodeInfo? = null) {
        val cache = if (inFeed) FEED_CACHE_MS else OUT_FEED_CACHE_MS
        if (now - feedCheckedAt < cache) return
        val root = preloaded ?: rootInActiveWindow
        // A missing or mismatched root is common for a few frames during app/window transitions. Retry soon
        // (but never on every scroll event) and do not turn it into a feed miss; foreground verification
        // decides whether the app was really left.
        if (root == null || root.packageName?.toString() != src.pkg) {
            feedCheckedAt = now - cache + ROOT_RETRY_MS
            return
        }
        feedCheckedAt = now

        val pager = ids(src)
        var found = pager.tabIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").any { it.isSelected }
        } || pager.viewerIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").isNotEmpty()
        }

        // App updates occasionally rename internal IDs. Only after a miss, only while the feed was just open or
        // the app is actually being scrolled, and at a low frequency, fall back to stable accessibility labels
        // such as a selected "Reels"/"Shorts" tab. The traversal is bounded.
        if (!found && feedMisses >= 1 && now - fallbackCheckedAt >= FALLBACK_SCAN_MS &&
            (inFeed || now - lastSeenScrollAt < FALLBACK_SCROLL_WINDOW_MS)
        ) {
            fallbackCheckedAt = now
            found = scanner.hasSelectedLabel(root, src.label)
        }

        if (found) {
            feedMisses = 0
            setInFeed(true, now)
        } else if (++feedMisses >= FEED_MISSES_TO_LEAVE) {
            setInFeed(false, now)
        }
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
        ReelRepository.store.addWatchMs(watchPendingMs)
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
        if (src != null && AppSettings.overlayEnabled.value && (ids(src).popupEverywhere || inFeed)) overlay.show()
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
        foregroundCheckJob?.cancel()
        val now = SystemClock.uptimeMillis()
        resetTracking(now)
        endWatch(now)
        TrackingStatus.update(TrackingState.IDLE, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        val now = SystemClock.uptimeMillis()
        resetTracking(now)
        endWatch(now)
        TrackingStatus.update(TrackingState.IDLE, null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // Sleep lengths
        private const val TICK_MS = 1000L             // Instagram Reels tab
        private const val OUT_TICK_MS = 2500L         // tracked app open, but not in a feed
        private const val POLL_MS = 800L              // Shorts page read / home-feed retry while scrolling
        private const val IDLE_POLL_MS = 1500L        // Shorts page read while resting
        private const val HOME_IDLE_TICK_MS = 1200L
        private const val SETTLE_MS = 250L            // a scroll counts as finished after this long
        private const val MIN_WAKE_MS = 100L
        private const val POLL_SLACK_MS = 100L

        // Scroll bookkeeping
        private const val ACTIVE_SCROLL_MS = 3000L
        private const val BURST_GAP_MS = 1000L
        private const val HOME_ACTIVE_MS = 8000L      // keep reading the home feed this long after the last scroll
        private const val GESTURE_GAP_MS = 700L

        // Feed detection
        private const val FEED_CACHE_MS = 750L        // while in a feed
        private const val OUT_FEED_CACHE_MS = 1500L   // while not in a feed
        private const val FALLBACK_SCAN_MS = 2500L
        private const val FALLBACK_SCROLL_WINDOW_MS = 3000L
        private const val ROOT_RETRY_MS = 250L
        private const val FEED_MISSES_TO_LEAVE = 3
        private const val FOREGROUND_SETTLE_MS = 220L
        private const val FOREGROUND_RETRY_MS = 180L
        private const val FOREGROUND_CHECKS = 3

        // Watch time and memory
        private const val MAX_WATCH_GAP_MS = 3000L
        private const val WATCH_FLUSH_MS = 15_000L
        private const val MAX_COUNTED = 4000

        private const val NONE = PageScanner.NONE
        private const val UNKNOWN = PageScanner.UNKNOWN
        private const val NON_VIDEO = PageScanner.NON_VIDEO
    }
}
