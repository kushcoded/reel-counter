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
    const val DEFAULT_GOAL = 100
    const val MAX_TRANSPARENCY = 0.8f

    private lateinit var prefs: SharedPreferences
    private val _overlayEnabled = MutableStateFlow(true)
    private val _goal = MutableStateFlow(DEFAULT_GOAL)
    private val _transparency = MutableStateFlow(0.2f)
    private val _fallbackSeconds = MutableStateFlow(8)

    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()
    /** Daily goal; the popup turns red once today's count is above it. */
    val goal: StateFlow<Int> = _goal.asStateFlow()
    /** 0 = solid popup, up to MAX_TRANSPARENCY = mostly see-through. */
    val transparency: StateFlow<Float> = _transparency.asStateFlow()
    /** Used only when Instagram doesn't expose playback progress: a reel counts after this long. */
    val fallbackSeconds: StateFlow<Int> = _fallbackSeconds.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _overlayEnabled.value = prefs.getBoolean("overlay", true)
        _goal.value = prefs.getInt("goal", DEFAULT_GOAL)
        _transparency.value = prefs.getFloat("transparency", 0.2f)
        _fallbackSeconds.value = prefs.getInt("fallback_seconds", 8)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("overlay", enabled).apply()
        _overlayEnabled.value = enabled
    }

    fun setGoal(value: Int) {
        val v = value.coerceIn(1, 99_999)
        prefs.edit().putInt("goal", v).apply()
        _goal.value = v
    }

    fun setTransparency(value: Float) {
        val v = value.coerceIn(0f, MAX_TRANSPARENCY)
        prefs.edit().putFloat("transparency", v).apply()
        _transparency.value = v
    }

    fun setFallbackSeconds(value: Int) {
        val v = value.coerceIn(3, 30)
        prefs.edit().putInt("fallback_seconds", v).apply()
        _fallbackSeconds.value = v
    }

    var overlayX: Int
        get() = prefs.getInt("ox", 32)
        set(v) = prefs.edit().putInt("ox", v).apply()

    var overlayY: Int
        get() = prefs.getInt("oy", 260)
        set(v) = prefs.edit().putInt("oy", v).apply()
}
