package com.reelcounter.app

import java.time.LocalDate
import java.time.ZoneId

/**
 * Cheap "today" key. Computing a date string on every overlay refresh / service tick allocates;
 * this caches it and only recomputes when the clock passes midnight (or jumps backwards or the timezone changes).
 */
object DayClock {
    @Volatile private var key = ""
    @Volatile private var startsAt = Long.MAX_VALUE
    @Volatile private var endsAt = 0L

    fun today(): String {
        val now = System.currentTimeMillis()
        if (now < startsAt || now >= endsAt) refresh()
        return key
    }

    /** Forget the cached day (the clock or timezone changed); the next [today] call recomputes it. */
    fun invalidate() {
        startsAt = Long.MAX_VALUE
        endsAt = 0L
    }

    @Synchronized
    private fun refresh() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        key = day.toString()
        startsAt = day.atStartOfDay(zone).toInstant().toEpochMilli()
        endsAt = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
