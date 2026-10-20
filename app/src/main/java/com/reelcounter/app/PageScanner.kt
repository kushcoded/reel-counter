package com.reelcounter.app

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bounded, allocation-light reads of another app's accessibility tree.
 *
 * Every read is an IPC round trip per node, so all scans are capped, stop early when they have what they need,
 * and reuse one scratch [Rect] and one queue for the lifetime of the service (it runs 24/7, so per-scan garbage
 * would add up). Everything runs on the main thread, so the scratch objects need no locking.
 */
internal class PageScanner(private val resources: Resources) {

    private val rect = Rect()
    private val queue = ArrayDeque<AccessibilityNodeInfo>()

    // Home-feed cache: the big scrolling list, and the last post we fully read
    private var homeList: AccessibilityNodeInfo? = null
    private var lastHomeItem: AccessibilityNodeInfo? = null
    private var lastHomeKey: Int? = null
    private var lastHomeReadAt = 0L

    // Result of the last [scan]
    private var scanVideo = false
    private var scanHash = 0
    private var scanTexts = 0

    /** Drop cached nodes (left the app or feed) so stale references are not kept alive. */
    fun reset() {
        homeList = null
        lastHomeItem = null
        lastHomeKey = null
        queue.clear()
    }

    /** Does this scrolling view fill most of the screen (a pager), as opposed to a comment list or carousel? */
    fun looksLikePager(node: AccessibilityNodeInfo?): Boolean {
        node ?: return true
        node.getBoundsInScreen(rect)
        val dm = resources.displayMetrics
        return rect.height() >= dm.heightPixels * MIN_PAGER_HEIGHT_RATIO && rect.width() >= dm.widthPixels * MIN_PAGER_WIDTH_RATIO
    }

    /** Fallback feed detection: a selected tab labelled [label] in the bottom navigation bar. */
    fun hasSelectedLabel(root: AccessibilityNodeInfo, label: String): Boolean {
        val navTop = resources.displayMetrics.heightPixels * BOTTOM_NAV_TOP_RATIO
        var visited = 0
        queue.clear()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < MAX_FALLBACK_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isSelected && node.isVisibleToUser && (labelMatches(node.text, label) || labelMatches(node.contentDescription, label))) {
                node.getBoundsInScreen(rect)
                if (rect.top >= navTop) { queue.clear(); return true }
            }
            enqueueChildren(node)
        }
        queue.clear()
        return false
    }

    /**
     * Shorts: identity of the page in the middle of the pager, from a hash of its first few readable texts.
     * Null when there is no pager or nothing readable yet.
     */
    fun readPagerKey(root: AccessibilityNodeInfo, pkg: String, viewerIds: List<String>): Int? {
        var found: AccessibilityNodeInfo? = null
        for (id in viewerIds) {
            found = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()
            if (found != null) break
        }
        val pager = found ?: return null
        val page = centredChild(pager) ?: pager
        scan(page, detectVideo = false, maxNodes = MAX_PAGE_NODES)
        return if (scanTexts == 0) null else finishKey()
    }

    /**
     * Instagram home feed: key of the post in the middle of the screen. [NON_VIDEO] for photos and anything
     * that is not a reel/video, otherwise a hash of its first readable texts; null when it can't be told yet.
     */
    fun readHomeKey(root: AccessibilityNodeInfo, now: Long, lastScrollAt: Long): Int? {
        val list = findFeedList(root) ?: return null
        val item = centredChild(list) ?: return null     // [rect] now holds the item's bounds
        // Grid rows (Explore, profiles) and comments are much shorter than a post.
        if (rect.height() < resources.displayMetrics.heightPixels * MIN_POST_HEIGHT_RATIO) {
            lastHomeItem = null
            return NON_VIDEO
        }
        // Same post still in the middle and nothing scrolled since: reuse the previous answer.
        if (item == lastHomeItem && lastHomeKey != null && lastScrollAt <= lastHomeReadAt) return lastHomeKey
        lastHomeItem = item
        lastHomeReadAt = now

        scan(item, detectVideo = true, maxNodes = MAX_ITEM_NODES)
        val key = when {
            !scanVideo -> NON_VIDEO
            scanTexts == 0 -> null                       // a reel, but nothing readable yet: look again later
            else -> finishKey()
        }
        lastHomeKey = key
        return key
    }

    // ------------------------------------------------------------------ internals

    /** The big vertically scrolling list that holds the feed's posts (found by size, not by id; cached). */
    private fun findFeedList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        homeList?.let { if (it.refresh() && it.isVisibleToUser) return it }
        homeList = null
        val dm = resources.displayMetrics
        var visited = 0
        queue.clear()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < MAX_LIST_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable) {
                node.getBoundsInScreen(rect)
                if (rect.height() >= dm.heightPixels * MIN_LIST_HEIGHT_RATIO && rect.width() >= dm.widthPixels * MIN_PAGER_WIDTH_RATIO) {
                    queue.clear()
                    homeList = node
                    return node
                }
            }
            enqueueChildren(node)
        }
        queue.clear()
        return null
    }

    /** The child of [parent] that covers the vertical centre of the screen; leaves its bounds in [rect]. */
    private fun centredChild(parent: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val centre = resources.displayMetrics.heightPixels / 2
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            child.getBoundsInScreen(rect)
            if (rect.top <= centre && rect.bottom >= centre) return child
        }
        return null
    }

    /**
     * Breadth-first read of [item]: hashes its first few readable texts and, if asked, looks for a video marker.
     * Stops as soon as the answer cannot change any more. Results land in the scan* fields.
     */
    private fun scan(item: AccessibilityNodeInfo, detectVideo: Boolean, maxNodes: Int) {
        scanVideo = false
        scanHash = 17
        scanTexts = 0
        var visited = 0
        queue.clear()
        queue.addLast(item)
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++
            if (detectVideo && !scanVideo) scanVideo = isVideoNode(node)
            if (scanTexts < MAX_TEXTS) {
                val t = node.text
                if (t != null && t.length >= MIN_TEXT_LEN && node.isVisibleToUser && !isCountLike(t)) {
                    var h = scanHash
                    for (i in 0 until t.length) h = 31 * h + t[i].code
                    scanHash = h
                    scanTexts++
                }
            }
            if (scanTexts >= MAX_TEXTS && (!detectVideo || scanVideo)) break
            enqueueChildren(node)
        }
        queue.clear()
    }

    private fun enqueueChildren(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
    }

    private fun finishKey(): Int {
        val h = scanHash
        return if (h == NONE || h == UNKNOWN || h == NON_VIDEO) h + 3 else h
    }

    private fun isVideoNode(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName
        if (id != null && (id.contains("video", ignoreCase = true) || id.contains("clips", ignoreCase = true))) return true
        val d = node.contentDescription
        return d != null && (d.startsWith("Reel", ignoreCase = true) || d.startsWith("Video", ignoreCase = true))
    }

    /** "1.2M", "345", "12K": bare counts change while you watch, so they must not be part of an identity. */
    private fun isCountLike(t: CharSequence): Boolean {
        var digits = 0
        for (i in 0 until t.length) {
            val c = t[i]
            when {
                c.isDigit() -> digits++
                c == '.' || c == ',' || c == ' ' || c == '+' -> Unit
                (c == 'K' || c == 'k' || c == 'M' || c == 'm' || c == 'B' || c == 'b') && i == t.length - 1 -> Unit
                else -> return false
            }
        }
        return digits > 0
    }

    private fun labelMatches(cs: CharSequence?, label: String): Boolean =
        cs != null && cs.length == label.length && cs.toString().equals(label, ignoreCase = true)

    companion object {
        // Sentinels shared with the service. Real keys are nudged away from them in [finishKey].
        const val NONE = Int.MIN_VALUE             // not in a pager
        const val UNKNOWN = Int.MIN_VALUE + 1      // in a pager, position not known yet
        const val NON_VIDEO = Int.MIN_VALUE + 2    // a home-feed post that is not a reel/video

        private const val MIN_PAGER_HEIGHT_RATIO = 0.6f
        private const val MIN_PAGER_WIDTH_RATIO = 0.8f
        private const val BOTTOM_NAV_TOP_RATIO = 0.8f
        private const val MIN_POST_HEIGHT_RATIO = 0.4f
        private const val MIN_LIST_HEIGHT_RATIO = 0.5f
        private const val MAX_FALLBACK_NODES = 64
        private const val MAX_LIST_SEARCH_NODES = 80
        private const val MAX_PAGE_NODES = 40
        private const val MAX_ITEM_NODES = 60
        private const val MAX_TEXTS = 6
        private const val MIN_TEXT_LEN = 4
    }
}
