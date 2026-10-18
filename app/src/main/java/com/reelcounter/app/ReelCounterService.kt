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
import java.time.LocalDate

/**
 * Watches Instagram Reels and counts a reel once it has been watched ~30%.
 *
 * How a reel is counted:
 *  - The reel on screen is tracked by its position in the pager (index).
 *  - A ticker checks it every [TICK_MS]. If Instagram exposes the playback progress bar,
 *    the reel counts once progress reaches [WATCH_FRACTION]. Otherwise it counts after the
 *    "fallback seconds" setting.
 *  - Every counted index is remembered, so scrolling back up and down again never counts twice.
 *
 * Everything Instagram-specific lives in the constants at the bottom.
 */
class ReelCounterService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: ReelOverlay
    private var tickerJob: Job? = null

    private var igForeground = false
    private var imePackage: String? = null

    // Reel tracking state
    private var currentIndex = NONE
    private var currentSince = 0L
    private var currentCounted = false
    private var maxFraction = 0f
    private var progressSeen = false
    private val countedIndices = HashSet<Int>()
    private var leftAt = 0L

    // Scroll/gesture state
    private var lastScrollAt = 0L
    private var pseudoIndex = 0
    private var lastIndexWasReal = false

    // Cached playback-progress bar (looking it up is expensive, reading it is cheap)
    private var progressNode: AccessibilityNodeInfo? = null
    private var progressSearchAt = 0L
    private var lastDay = ""

    // Reels-screen detection cache
    private var reelsCheckedAt = 0L
    private var reelsCached = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = setForeground(false)
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
            combine(ReelRepository.counts, AppSettings.limit, AppSettings.transparency) { _, _, _ -> }
                .collect { overlay.refresh() }
        }
        scope.launch { AppSettings.overlayEnabled.collect { syncOverlay() } }

        updateEventTypes(instagramOpen = false)   // scroll events only matter while Instagram is open
        if (rootInActiveWindow?.packageName?.toString() == INSTAGRAM) { setForeground(true); syncOverlay() }
    }

    // ------------------------------------------------------------------ events

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == INSTAGRAM) onScroll(event)
        }
    }

    private fun onWindowChanged(pkg: String, className: String?) {
        when {
            pkg == INSTAGRAM -> { setForeground(true); syncOverlay() }
            // Status bar, keyboard and our own overlay must not count as "left Instagram".
            pkg == "com.android.systemui" || pkg == imePackage -> Unit
            pkg == packageName && className != MainActivity::class.java.name -> Unit
            else -> setForeground(false)
        }
    }

    private fun onScroll(event: AccessibilityEvent) {
        setForeground(true)
        if (!isInReels()) { resetTracking(); return }

        val now = SystemClock.uptimeMillis()
        val realIndex = if (event.fromIndex >= 0 && event.itemCount > 0) event.fromIndex else -1

        // Cheap path: nearly every scroll event (one per few pixels of a swipe) is for the reel we
        // already track, so skip the expensive checks below.
        if (realIndex >= 0 && realIndex == currentIndex) { lastScrollAt = now; return }
        if (!looksLikeReelPager(event.source)) return

        lastIndexWasReal = realIndex >= 0
        val index = if (realIndex >= 0) realIndex else nextGestureIndex(event, now)
        lastScrollAt = now

        when {
            currentIndex == NONE -> startTracking(index, now)
            currentIndex == UNKNOWN && lastIndexWasReal -> {
                // First scroll after entering Reels: the reel we were already timing is `index`.
                currentIndex = index
                if (currentCounted) countedIndices.add(index) else if (index in countedIndices) currentCounted = true
            }
            currentIndex == UNKNOWN || index != currentIndex -> {
                // Pager jumped back to the very top after being deep in the feed = refreshed feed.
                if (index == 0 && currentIndex >= 3) countedIndices.clear()
                startTracking(index, now)
            }
        }
    }

    /** Fallback when Instagram reports no pager position: one step per swipe gesture, direction if known. */
    private fun nextGestureIndex(event: AccessibilityEvent, now: Long): Int {
        if (now - lastScrollAt > GESTURE_GAP_MS) {
            val backwards = Build.VERSION.SDK_INT >= 28 && event.scrollDeltaY < 0
            pseudoIndex += if (backwards) -1 else 1
        }
        return pseudoIndex
    }

    // ------------------------------------------------------------------ watching logic

    private fun setForeground(value: Boolean) {
        if (igForeground == value) return
        igForeground = value
        updateEventTypes(value)
        if (value) {
            tickerJob = scope.launch {
                while (isActive) {
                    // Poll fast only while a reel is being timed, slowly otherwise.
                    delay(if (currentIndex != NONE && !currentCounted) TICK_MS else IDLE_TICK_MS)
                    tick()
                }
            }
        } else {
            tickerJob?.cancel()
            tickerJob = null
            resetTracking()
            // Instagram was closed: forget which reels were counted, so its next session
            // (which starts again from position 0) never has new reels skipped.
            countedIndices.clear()
            pseudoIndex = 0
        }
        syncOverlay()
    }

    /** Other apps' scroll events are pure overhead, so only subscribe to them while Instagram is open. */
    private fun updateEventTypes(instagramOpen: Boolean) {
        val info = serviceInfo ?: return
        val wanted = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (instagramOpen) AccessibilityEvent.TYPE_VIEW_SCROLLED else 0)
        if (info.eventTypes != wanted) {
            info.eventTypes = wanted
            serviceInfo = info
        }
    }

    private fun startTracking(index: Int, now: Long) {
        currentIndex = index
        currentSince = now
        currentCounted = index != UNKNOWN && index in countedIndices
        maxFraction = 0f
        progressSeen = false
        progressNode = null
        progressSearchAt = 0L
    }

    private fun resetTracking() {
        if (currentIndex != NONE) leftAt = SystemClock.uptimeMillis()
        currentIndex = NONE
        currentCounted = false
        maxFraction = 0f
        progressSeen = false
        progressNode = null
    }

    private fun tick() {
        if (!igForeground) return
        val day = LocalDate.now().toString()
        if (day != lastDay) { lastDay = day; overlay.refresh() }   // new day: popup goes back to 0
        if (!isInReels()) { resetTracking(); return }
        val now = SystemClock.uptimeMillis()
        if (currentIndex == NONE) {
            // New visit to Reels. Keep old "already counted" memory unless we've been away a while.
            if (now - leftAt > SESSION_TIMEOUT_MS) countedIndices.clear()
            startTracking(UNKNOWN, now)
            return
        }
        if (currentCounted) return
        val dwell = now - currentSince
        if (dwell < MIN_DWELL_MS) return

        readProgress(now)?.let { f ->
            if (f > 0f) { progressSeen = true; if (f > maxFraction) maxFraction = f }
        }
        val watchedEnough = if (progressSeen) maxFraction >= WATCH_FRACTION
        else dwell >= AppSettings.fallbackSeconds.value * 1000L

        if (watchedEnough) {
            currentCounted = true
            if (currentIndex != UNKNOWN) countedIndices.add(currentIndex)
            ReelRepository.adjustToday(1)
        }
    }

    /** Playback progress (0..1) if Instagram exposes its thin progress bar to accessibility, else null. */
    private fun readProgress(now: Long): Float? {
        var node = progressNode
        if (node != null && !(node.refresh() && node.isVisibleToUser)) { node = null; progressNode = null }
        if (node == null) {
            if (now - progressSearchAt < PROGRESS_SEARCH_INTERVAL_MS) return null
            progressSearchAt = now
            node = findProgressNode() ?: return null
            progressNode = node
        }
        val info = (node ?: return null).rangeInfo ?: return null
        if (info.max <= info.min) return null
        return ((info.current - info.min) / (info.max - info.min)).coerceIn(0f, 1f)
    }

    /** A wide, thin range-type node in the lower half of the Instagram window. */
    private fun findProgressNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != INSTAGRAM) return null
        val dm = resources.displayMetrics
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        val r = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val info = node.rangeInfo
            if (info != null && info.max > info.min && node.isVisibleToUser) {
                node.getBoundsInScreen(r)
                if (r.width() >= dm.widthPixels * 0.6f && r.height() <= 48 * dm.density && r.top >= dm.heightPixels * 0.5f) {
                    return node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return null
    }

    // ------------------------------------------------------------------ screen checks

    /** True when the Reels tab is selected or the full-screen reels viewer is on screen. */
    private fun isInReels(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - reelsCheckedAt < 500) return reelsCached
        val root = rootInActiveWindow ?: return reelsCached
        if (root.packageName?.toString() != INSTAGRAM) return reelsCached
        reelsCheckedAt = now
        reelsCached = REELS_TAB_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId("$INSTAGRAM:id/$id").any { it.isSelected }
        } || VIEWER_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId("$INSTAGRAM:id/$id").isNotEmpty()
        }
        return reelsCached
    }

    /** Ignores comment lists, carousels, etc. - the reels pager fills most of the screen. */
    private fun looksLikeReelPager(node: AccessibilityNodeInfo?): Boolean {
        node ?: return true
        val r = Rect()
        node.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        return r.height() >= dm.heightPixels * MIN_HEIGHT_RATIO && r.width() >= dm.widthPixels * MIN_WIDTH_RATIO
    }

    private fun syncOverlay() {
        if (igForeground && AppSettings.overlayEnabled.value) overlay.show() else overlay.hide()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (::overlay.isInitialized) overlay.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val INSTAGRAM = "com.instagram.android"

        // --- Tuning knobs -------------------------------------------------------------
        /** View ids (without package prefix) of the bottom-bar Reels tab. */
        private val REELS_TAB_IDS = listOf("clips_tab")
        /** View ids that exist only inside the full-screen reels viewer. */
        private val VIEWER_IDS = listOf("clips_viewer_view_pager")

        private const val WATCH_FRACTION = 0.3f          // "watched 30% or more"
        private const val MIN_DWELL_MS = 1000L           // ignore progress readings right after a swipe
        private const val TICK_MS = 400L
        private const val IDLE_TICK_MS = 1200L
        private const val SESSION_TIMEOUT_MS = 120_000L  // forget "already counted" after 2 min away
        private const val MAX_NODES = 250
        private const val PROGRESS_SEARCH_INTERVAL_MS = 1500L
        private const val MIN_HEIGHT_RATIO = 0.6f
        private const val MIN_WIDTH_RATIO = 0.8f
        private const val GESTURE_GAP_MS = 700L

        private const val NONE = Int.MIN_VALUE
        private const val UNKNOWN = Int.MIN_VALUE + 1
    }
}
