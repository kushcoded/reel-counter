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
 * Counts Instagram Reels and YouTube Shorts into one merged total (1 short = 1 reel) and keeps the floating
 * counter in sync.
 *
 * A reel/short counts once you have stayed on it for the configured number of seconds. Each pager position is
 * remembered, so scrolling back up and down never counts the same one twice; that memory is wiped whenever you
 * leave the Reels/Shorts screen or the app, so a fresh session is never skipped.
 *
 * An app switched off in the settings is ignored completely: no counting, no popup, no scroll events.
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
         * true: identify it by the readable text on the page in the middle of the screen, polled
         * lightly - scroll events from this pager are unreliable, so they only wake the poll (Shorts).
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
            listOf("clips_tab"), listOf("clips_viewer_view_pager"), popupEverywhere = true, readsPageKey = false, homeFeed = true
        )
        Platform.Shorts -> PagerIds(
            emptyList(), listOf("reel_recycler", "reel_player_page_container", "reel_watch_fragment_root"),
            popupEverywhere = false, readsPageKey = true, homeFeed = false
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: ReelOverlay
    private var tickerJob: Job? = null
    private var foregroundCheckJob: Job? = null
    private var imePackage: String? = null

    // Which tracked app is in the foreground, and whether its Reels/Shorts pager is on screen
    private var active: Platform? = null
    private var inFeed = false
    private var feedCheckedAt = 0L
    private var fallbackCheckedAt = 0L
    private var feedMisses = 0      // consecutive "pager not found" checks; leaving needs several confirmations
    private var lastPollAt = 0L
    // Home-feed mode (Instagram only): reels inside the ordinary feed, found while the user is scrolling it
    private var inHome = false
    private var homeList: AccessibilityNodeInfo? = null
    private var lastHomeItem: AccessibilityNodeInfo? = null
    private var lastHomeKey: Int? = null
    private var lastHomeReadAt = 0L
    private var lastSeenScrollAt = 0L   // any scroll in the tracked app, even before a feed is detected

    // The reel/short currently on screen
    private var currentIndex = NONE
    private var currentSince = 0L
    private var currentCounted = false
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
        lastSeenScrollAt = now
        refreshFeedState(src, now)
        if (!inFeed) {
            if (ids(src).homeFeed && now - lastPollAt >= EVENT_POLL_MS) wakeHome(src, now)
            return
        }

        if (ids(src).readsPageKey) {
            if (now - lastPollAt >= EVENT_POLL_MS) pollPage(src, now, rootInActiveWindow)
            return
        }

        val realIndex = if (event.fromIndex >= 0 && event.itemCount > 0) event.fromIndex else -1

        // Cheap path: nearly every scroll event (one per few pixels of a swipe) is for the reel we
        // already track, so skip the expensive checks below.
        if (realIndex >= 0 && realIndex == currentIndex) { lastScrollAt = now; return }
        if (!looksLikePager(event.source)) return

        val index = if (realIndex >= 0) realIndex else nextGestureIndex(event, now)
        lastScrollAt = now
        if (index == currentIndex) return

        if (currentIndex == UNKNOWN && realIndex >= 0) {
            // First scroll after entering: the reel we have been timing all along is `index`.
            currentIndex = index
            if (currentCounted) countedIndices.add(index) else if (index in countedIndices) currentCounted = true
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

    /** Count the reel on screen once it has been there long enough (and was not counted before). */
    private fun countIfDue(now: Long) {
        if (currentIndex == NONE || currentCounted) return
        if (currentIndex == UNKNOWN && inHome) return   // home feed: wait until the item is identified
        if (now - currentSince < AppSettings.seconds.value * 1000L) return
        currentCounted = true
        if (currentIndex == UNKNOWN || countedIndices.add(currentIndex)) ReelRepository.store.adjustToday(1)
    }

    /** The reel/short on screen changed (or was seen for the first time): finish the old one, time the new one. */
    private fun observePage(key: Int, now: Long) {
        if (currentIndex == NONE || key == currentIndex) return
        if (currentIndex == UNKNOWN) {
            // First look after entering: this is the page we have been timing all along.
            currentIndex = key
            if (key == NON_VIDEO) currentCounted = true
            else if (currentCounted) countedIndices.add(key) else if (key in countedIndices) currentCounted = true
        } else {
            countIfDue(now)
            startReel(key, now)
        }
    }

    private fun pollPage(src: Platform, now: Long, root: AccessibilityNodeInfo?) {
        lastPollAt = now
        if (root == null || root.packageName?.toString() != src.pkg) return
        readPageKey(src, root)?.let { observePage(it, now) }
    }

    /**
     * Identity of the page in the middle of the screen: a hash of its first few readable texts (caption,
     * channel, sound...). Bare counts like "1.2M" are skipped because they change while you watch.
     * Bounded to a handful of nodes, and only run while a pager is open.
     */
    private fun readPageKey(src: Platform, root: AccessibilityNodeInfo): Int? {
        val pager = ids(src).viewerIds.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").firstOrNull()
        } ?: return null
        val centre = resources.displayMetrics.heightPixels / 2
        val r = Rect()
        var page = pager
        for (i in 0 until pager.childCount) {
            val child = pager.getChild(i) ?: continue
            child.getBoundsInScreen(r)
            if (r.top <= centre && r.bottom >= centre) { page = child; break }
        }

        var hash = 17
        var texts = 0
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(page)
        while (queue.isNotEmpty() && visited < MAX_PAGE_NODES && texts < MAX_PAGE_TEXTS) {
            val node = queue.removeFirst()
            visited++
            val t = node.text
            if (t != null && t.length >= MIN_TEXT_LEN && node.isVisibleToUser && !COUNT_LIKE.matches(t)) {
                hash = 31 * hash + t.toString().hashCode()
                texts++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        if (texts == 0) return null
        return if (hash == NONE || hash == UNKNOWN) hash + 2 else hash
    }

    // ------------------------------------------------------------------ home feed (Instagram FYP)

    /** The user is scrolling the home feed: start identifying which post is in the middle of the screen. */
    private fun wakeHome(src: Platform, now: Long) {
        if (!inHome) enterHome(now)
        pollHome(src, now)
    }

    private fun enterHome(now: Long) {
        inHome = true
        startReel(UNKNOWN, now)
        updateTrackingStatus()
    }

    /** Scrolling stopped for a while: stop reading the screen. Already counted items stay remembered. */
    private fun pauseHome(now: Long) {
        if (!inHome) return
        countIfDue(now)
        inHome = false
        currentIndex = NONE
        currentCounted = false
        homeList = null
        lastHomeItem = null
        lastHomeKey = null
        updateTrackingStatus()
    }

    /** Periodic part of home-feed counting; returns how long to sleep. */
    private fun tickHome(src: Platform, now: Long): Long {
        if (now - lastSeenScrollAt >= HOME_ACTIVE_MS) { pauseHome(now); return TICK_MS }
        if (!inHome) enterHome(now)
        pollHome(src, now)
        countIfDue(now)
        if (currentIndex == NONE || currentCounted) return POLL_MS
        val due = currentSince + AppSettings.seconds.value * 1000L
        return (due - now).coerceIn(MIN_WAKE_MS, POLL_MS)
    }

    private fun pollHome(src: Platform, now: Long) {
        lastPollAt = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != src.pkg) return
        readHomeKey(root, now)?.let { observePage(it, now) }
    }

    /**
     * Identity of the post in the middle of the home feed: [NON_VIDEO] for photos and other non-reel posts,
     * otherwise a hash of its first readable texts. Looks for a video marker (a "video"/"clips" view id, or a
     * description starting with "Reel"/"Video") so photos are never counted. The expensive read is skipped
     * while the same post is still in the middle and nothing has scrolled since.
     */
    private fun readHomeKey(root: AccessibilityNodeInfo, now: Long): Int? {
        val list = findFeedList(root) ?: return null
        val dm = resources.displayMetrics
        val centre = dm.heightPixels / 2
        val r = Rect()
        var item: AccessibilityNodeInfo? = null
        for (i in 0 until list.childCount) {
            val child = list.getChild(i) ?: continue
            child.getBoundsInScreen(r)
            if (r.top <= centre && r.bottom >= centre) { item = child; break }
        }
        if (item == null) return null
        item.getBoundsInScreen(r)
        // Grid rows (Explore, profiles) and comments are much shorter than a post.
        if (r.height() < dm.heightPixels * MIN_POST_HEIGHT_RATIO) return NON_VIDEO.also { lastHomeItem = null }

        if (item == lastHomeItem && lastHomeKey != null && lastSeenScrollAt <= lastHomeReadAt) return lastHomeKey
        lastHomeItem = item
        lastHomeReadAt = now

        var video = false
        var hash = 17
        var texts = 0
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(item)
        while (queue.isNotEmpty() && visited < MAX_ITEM_NODES) {
            val node = queue.removeFirst()
            visited++
            if (!video) {
                val id = node.viewIdResourceName
                val desc = node.contentDescription
                video = (id != null && VIDEO_ID.containsMatchIn(id)) || (desc != null && VIDEO_DESC.containsMatchIn(desc))
            }
            if (texts < MAX_PAGE_TEXTS) {
                val t = node.text
                if (t != null && t.length >= MIN_TEXT_LEN && node.isVisibleToUser && !COUNT_LIKE.matches(t)) {
                    hash = 31 * hash + t.toString().hashCode()
                    texts++
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        val key = when {
            !video -> NON_VIDEO
            texts == 0 -> null                // a reel, but nothing readable yet: look again next poll
            hash == NONE || hash == UNKNOWN || hash == NON_VIDEO -> hash + 3
            else -> hash
        }
        lastHomeKey = key
        return key
    }

    /** The big vertically scrolling list that holds the feed's posts (cached; found by size, not by id). */
    private fun findFeedList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        homeList?.let { if (it.refresh() && it.isVisibleToUser) return it }
        homeList = null
        val dm = resources.displayMetrics
        val r = Rect()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < MAX_LIST_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable) {
                node.getBoundsInScreen(r)
                if (r.height() >= dm.heightPixels * MIN_LIST_HEIGHT_RATIO && r.width() >= dm.widthPixels * MIN_WIDTH_RATIO) {
                    homeList = node
                    return node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    /** Forget the current pager session. Called when leaving the pager or the app. */
    private fun resetTracking(now: Long) {
        countIfDue(now)   // a reel watched long enough right before leaving still counts
        inHome = false
        homeList = null
        lastHomeItem = null
        lastHomeKey = null
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
        tickerJob = if (src != null) scope.launch { while (isActive) delay(tick()) } else null
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
        } else {
            endWatch(now)
            resetTracking(now)
        }
        updateTrackingStatus()
        syncOverlay()
    }

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
     * The only periodic work: roughly once a second while a tracked app is open, and a precisely timed wake-up
     * when a reel is about to reach the threshold. Returns how long to sleep.
     */
    private fun tick(): Long {
        val src = active ?: return TICK_MS
        val day = DayClock.today()
        if (day != lastDay) { lastDay = day; overlay.refresh() }   // new day: popup goes back to 0
        val now = SystemClock.uptimeMillis()
        val readsPageKey = ids(src).readsPageKey
        // Page identity is the expensive part. Reuse a root at the faster rate only while actually in
        // Shorts; outside the feed, regular once-per-second detection is enough.
        val root = if (readsPageKey && inFeed) rootInActiveWindow else null
        refreshFeedState(src, now, root)
        val interval = if (readsPageKey && inFeed) POLL_MS else TICK_MS
        if (!inFeed) return if (ids(src).homeFeed) tickHome(src, now) else interval

        if (readsPageKey) pollPage(src, now, root ?: rootInActiveWindow)
        accountWatch(now)
        if (watchPendingMs >= WATCH_FLUSH_MS) flushWatch()

        countIfDue(now)
        if (currentIndex == NONE || currentCounted) return interval
        val due = currentSince + AppSettings.seconds.value * 1000L
        return (due - now).coerceIn(MIN_WAKE_MS, interval)
    }

    /** Is the Reels/Shorts pager on screen? Cached, since each lookup is an IPC round trip. */
    private fun refreshFeedState(src: Platform, now: Long, preloaded: AccessibilityNodeInfo? = null) {
        if (now - feedCheckedAt < FEED_CACHE_MS) return
        val root = preloaded ?: rootInActiveWindow
        // A missing or mismatched root is common for a few frames during app/window transitions. Retry soon
        // (but never on every scroll event) and do not turn it into a feed miss; foreground verification
        // decides whether the app was really left.
        if (root == null || root.packageName?.toString() != src.pkg) {
            feedCheckedAt = now - FEED_CACHE_MS + ROOT_RETRY_MS
            return
        }
        feedCheckedAt = now

        val pager = ids(src)
        var found = pager.tabIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").any { it.isSelected }
        } || pager.viewerIds.any { id ->
            root.findAccessibilityNodeInfosByViewId("${src.pkg}:id/$id").isNotEmpty()
        }

        // App updates occasionally rename internal IDs. Only after repeated misses, and at a much lower
        // frequency, fall back to stable accessibility labels such as a selected "Reels"/"Shorts" tab.
        // The traversal is bounded, so it cannot turn into an unbounded tree scan.
        // Only while the feed was just open or the app is actually being scrolled, so ordinary non-feed
        // screens never pay for the scan.
        if (!found && feedMisses >= 1 && now - fallbackCheckedAt >= FALLBACK_SCAN_MS &&
            (inFeed || now - lastSeenScrollAt < FALLBACK_SCROLL_WINDOW_MS)
        ) {
            fallbackCheckedAt = now
            found = hasSelectedFeedLabel(src, root)
        }

        if (found) {
            feedMisses = 0
            setInFeed(true, now)
        } else if (++feedMisses >= FEED_MISSES_TO_LEAVE) {
            setInFeed(false, now)
        }
    }

    private fun hasSelectedFeedLabel(src: Platform, root: AccessibilityNodeInfo): Boolean {
        val wanted = src.label
        // The feed's tab lives in the bottom navigation bar; a selected "Reels" sub-tab on someone's profile
        // (higher up the screen) must not count as the feed.
        val navTop = resources.displayMetrics.heightPixels * BOTTOM_NAV_TOP_RATIO
        val r = Rect()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < MAX_FALLBACK_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isSelected) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                if (text.equals(wanted, ignoreCase = true) || desc.equals(wanted, ignoreCase = true)) {
                    node.getBoundsInScreen(r)
                    if (r.top >= navTop) return true
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
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
        private const val TICK_MS = 1000L
        private const val MIN_WAKE_MS = 100L
        private const val FEED_CACHE_MS = 750L
        private const val POLL_MS = 800L          // Shorts page identity; enough for a 3s+ count threshold
        private const val EVENT_POLL_MS = 400L    // scroll events may wake a read, but never faster than this
        private const val FALLBACK_SCAN_MS = 2500L
        private const val FALLBACK_SCROLL_WINDOW_MS = 3000L
        private const val ROOT_RETRY_MS = 250L
        private const val BOTTOM_NAV_TOP_RATIO = 0.8f
        private const val FEED_MISSES_TO_LEAVE = 3
        private const val MAX_FALLBACK_NODES = 64
        private const val MAX_PAGE_NODES = 40
        private const val MAX_PAGE_TEXTS = 6
        private const val MIN_TEXT_LEN = 4
        private val COUNT_LIKE = Regex("^[\\d.,]+\\s?[KkMmBb]?\\+?$")
        private const val MAX_WATCH_GAP_MS = 3000L
        private const val WATCH_FLUSH_MS = 15_000L
        private const val MIN_HEIGHT_RATIO = 0.6f
        private const val MIN_WIDTH_RATIO = 0.8f
        private const val GESTURE_GAP_MS = 700L
        private const val FOREGROUND_SETTLE_MS = 220L
        private const val FOREGROUND_RETRY_MS = 180L
        private const val FOREGROUND_CHECKS = 3
        private const val HOME_ACTIVE_MS = 8000L      // keep reading the home feed this long after the last scroll
        private const val MAX_ITEM_NODES = 60
        private const val MAX_LIST_SEARCH_NODES = 80
        private const val MIN_POST_HEIGHT_RATIO = 0.4f
        private const val MIN_LIST_HEIGHT_RATIO = 0.5f
        private val VIDEO_ID = Regex("video|clips", RegexOption.IGNORE_CASE)
        private val VIDEO_DESC = Regex("^(reel|video)\\b", RegexOption.IGNORE_CASE)
        private const val NON_VIDEO = Int.MIN_VALUE + 2   // a post in the home feed that is not a reel/video
        private const val NONE = Int.MIN_VALUE        // not in a pager
        private const val UNKNOWN = Int.MIN_VALUE + 1 // in a pager, position not known yet
    }
}
