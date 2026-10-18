package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches Instagram for "swiped to the next reel" and keeps the floating counter in sync.
 *
 * Everything Instagram-specific lives in the constants at the bottom. Instagram changes its
 * internals from time to time, so if counting stops working, that is the place to tune.
 */
class ReelCounterService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: ReelOverlay

    private var igForeground = false
    private var imePackage: String? = null

    // Reel detection state
    private var lastIndex = -1
    private var lastScrollAt = 0L
    private var lastCountAt = 0L
    private var reelsCheckedAt = 0L
    private var reelsCached = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            igForeground = false
            lastIndex = -1
            syncOverlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ReelRepository.init(this)
        AppSettings.init(this)
        overlay = ReelOverlay(this)
        imePackage = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch { ReelRepository.counts.collect { if (igForeground) overlay.update(ReelRepository.todayCount()) } }
        scope.launch { AppSettings.overlayEnabled.collect { syncOverlay() } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString())
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == INSTAGRAM) onScroll(event)
        }
    }

    private fun onWindowChanged(pkg: String, className: String?) {
        when {
            pkg == INSTAGRAM -> {
                if (!igForeground) { igForeground = true; lastIndex = -1 }
                syncOverlay()
            }
            // Status bar, keyboard and our own overlay must not count as "left Instagram".
            pkg == "com.android.systemui" || pkg == imePackage -> Unit
            pkg == packageName && className != MainActivity::class.java.name -> Unit
            else -> {
                igForeground = false
                lastIndex = -1
                syncOverlay()
            }
        }
    }

    private fun onScroll(event: AccessibilityEvent) {
        if (!igForeground) { igForeground = true; syncOverlay() }
        if (!isInReels()) { lastIndex = -1; return }
        if (!looksLikeReelPager(event.source)) return

        val now = SystemClock.uptimeMillis()
        val index = event.fromIndex
        if (index >= 0 && event.itemCount > 0) {
            // Preferred: the pager reports which item is showing. Count each step forward.
            if (lastIndex >= 0 && index > lastIndex) countReel(now)
            lastIndex = index
        } else if (now - lastScrollAt > GESTURE_GAP_MS) {
            // Fallback: no index info, so count one reel per swipe gesture.
            countReel(now)
        }
        lastScrollAt = now
    }

    private fun countReel(now: Long) {
        if (now - lastCountAt < MIN_COUNT_INTERVAL_MS) return
        lastCountAt = now
        ReelRepository.adjustToday(1)
    }

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
        if (igForeground && AppSettings.overlayEnabled.value) overlay.show(ReelRepository.todayCount())
        else overlay.hide()
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

        private const val MIN_HEIGHT_RATIO = 0.6f
        private const val MIN_WIDTH_RATIO = 0.8f
        private const val MIN_COUNT_INTERVAL_MS = 250L
        private const val GESTURE_GAP_MS = 700L
    }
}
