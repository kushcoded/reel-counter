package com.reelcounter.app

import java.util.Locale

object WatchTime {
    /** Average time per reel: 12.4s, or 1m 05s if very slow; "–" when there is nothing to average. */
    fun formatAvg(totalMs: Long, reels: Int): String {
        if (reels <= 0 || totalMs <= 0L) return "–"
        val avg = totalMs / reels
        return if (avg < 60_000L) String.format(Locale.getDefault(), "%.1fs", avg / 1000.0) else format(avg)
    }

    /** 45s, 12m 05s, 3h 20m */
    fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
            else -> "${s}s"
        }
    }
}
