package com.reelcounter.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Daily reel totals, keyed by ISO date ("2026-10-18"). Shared by the UI and the accessibility service. */
object ReelRepository {
    private lateinit var prefs: SharedPreferences
    private lateinit var watchPrefs: SharedPreferences
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    /** Milliseconds spent on the Reels screen, per day. */
    private val _watchMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val watchMs: StateFlow<Map<String, Long>> = _watchMs.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("reel_counts", Context.MODE_PRIVATE)
        _counts.value = prefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
        watchPrefs = context.applicationContext.getSharedPreferences("watch_time", Context.MODE_PRIVATE)
        _watchMs.value = watchPrefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    }

    fun todayCount(): Int = _counts.value[DayClock.today()] ?: 0

    @Synchronized
    fun adjustToday(delta: Int) {
        val key = DayClock.today()
        val updated = ((_counts.value[key] ?: 0) + delta).coerceAtLeast(0)
        prefs.edit().putInt(key, updated).apply()
        _counts.value = _counts.value + (key to updated)
    }

    /** Called by the service in batches (not per tick), so disk writes stay rare. */
    @Synchronized
    fun addWatchMs(delta: Long) {
        if (delta <= 0) return
        val key = DayClock.today()
        val updated = (_watchMs.value[key] ?: 0L) + delta
        watchPrefs.edit().putLong(key, updated).apply()
        _watchMs.value = _watchMs.value + (key to updated)
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
        watchPrefs.edit().clear().apply()
        _counts.value = emptyMap()
        _watchMs.value = emptyMap()
    }
}

/** User settings + remembered position of the floating counter. */
object AppSettings {
    const val DEFAULT_LIMIT = 100
    const val MAX_TRANSPARENCY = 1f
    const val DEFAULT_TRANSPARENCY = 0.5f
    const val DEFAULT_CM_PER_REEL = 13.5f
    const val MIN_CM_PER_REEL = 10f
    const val MAX_CM_PER_REEL = 17f

    private lateinit var prefs: SharedPreferences
    private val _overlayEnabled = MutableStateFlow(true)
    private val _limit = MutableStateFlow(DEFAULT_LIMIT)
    private val _transparency = MutableStateFlow(DEFAULT_TRANSPARENCY)
    private val _fallbackSeconds = MutableStateFlow(3)
    private val _cmPerReel = MutableStateFlow(DEFAULT_CM_PER_REEL)
    private val _darkTheme = MutableStateFlow(true)

    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()
    /** Daily limit; the popup turns red once today's count is above it. */
    val limit: StateFlow<Int> = _limit.asStateFlow()
    /** Transparency of the popup's dark background only (0 = solid, 1 = invisible). The number stays opaque. */
    val transparency: StateFlow<Float> = _transparency.asStateFlow()
    /** Used only when Instagram doesn't expose playback progress: a reel counts after this long. */
    val fallbackSeconds: StateFlow<Int> = _fallbackSeconds.asStateFlow()
    /** Thumb travel per reel, in cm; drives every distance figure. */
    val cmPerReel: StateFlow<Float> = _cmPerReel.asStateFlow()
    /** App theme; dark by default. */
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _overlayEnabled.value = prefs.getBoolean("overlay", true)
        _limit.value = prefs.getInt("limit", prefs.getInt("goal", DEFAULT_LIMIT))
        _transparency.value = prefs.getFloat("transparency", DEFAULT_TRANSPARENCY).coerceIn(0f, MAX_TRANSPARENCY)
        _fallbackSeconds.value = prefs.getInt("fallback_seconds", 3)
        _darkTheme.value = prefs.getBoolean("dark_theme", true)
        _cmPerReel.value = prefs.getFloat("cm_per_reel", DEFAULT_CM_PER_REEL).coerceIn(MIN_CM_PER_REEL, MAX_CM_PER_REEL)
    }

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean("dark_theme", dark).apply()
        _darkTheme.value = dark
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("overlay", enabled).apply()
        _overlayEnabled.value = enabled
    }

    fun setLimit(value: Int) {
        val v = value.coerceIn(1, 99_999)
        prefs.edit().putInt("limit", v).apply()
        _limit.value = v
    }

    /** Live update while the slider moves; call [persistTransparency] when the finger lifts. */
    fun setTransparency(value: Float) {
        _transparency.value = value.coerceIn(0f, MAX_TRANSPARENCY)
    }

    /** Live update while the slider moves; call [persistCmPerReel] when the finger lifts. */
    fun setCmPerReel(value: Float) {
        _cmPerReel.value = value.coerceIn(MIN_CM_PER_REEL, MAX_CM_PER_REEL)
    }

    fun persistCmPerReel() {
        prefs.edit().putFloat("cm_per_reel", _cmPerReel.value).apply()
    }

    fun persistTransparency() {
        prefs.edit().putFloat("transparency", _transparency.value).apply()
    }

    fun setFallbackSeconds(value: Int) {
        val v = value.coerceIn(1, 30)
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
