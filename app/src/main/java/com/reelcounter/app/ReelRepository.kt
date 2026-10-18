package com.reelcounter.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Daily reel totals, keyed by ISO date ("2026-10-18"). Shared by the UI and the accessibility service. */
object ReelRepository {
    private lateinit var prefs: SharedPreferences
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("reel_counts", Context.MODE_PRIVATE)
        _counts.value = prefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    }

    fun todayCount(): Int = _counts.value[LocalDate.now().toString()] ?: 0

    @Synchronized
    fun adjustToday(delta: Int) {
        val key = LocalDate.now().toString()
        val updated = ((_counts.value[key] ?: 0) + delta).coerceAtLeast(0)
        prefs.edit().putInt(key, updated).apply()
        _counts.value = _counts.value + (key to updated)
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
        _counts.value = emptyMap()
    }
}

/** User settings + remembered position of the floating counter. */
object AppSettings {
    private lateinit var prefs: SharedPreferences
    private val _overlayEnabled = MutableStateFlow(true)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _overlayEnabled.value = prefs.getBoolean("overlay", true)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("overlay", enabled).apply()
        _overlayEnabled.value = enabled
    }

    var overlayX: Int
        get() = prefs.getInt("ox", 32)
        set(v) = prefs.edit().putInt("ox", v).apply()

    var overlayY: Int
        get() = prefs.getInt("oy", 260)
        set(v) = prefs.edit().putInt("oy", v).apply()
}
