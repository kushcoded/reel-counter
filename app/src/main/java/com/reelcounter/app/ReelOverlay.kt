package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The tiny draggable pill. Uses TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps"
 * permission is needed - it comes with the accessibility service.
 *
 * The transparency setting fades only the dark background; the number stays fully opaque
 * (with a soft shadow so it still reads on bright video).
 */
class ReelOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // Read live: the display size / font scale can change while this long-lived service keeps running.
    private val density: Float get() = service.resources.displayMetrics.density
    private var addedAt = 0L
    private var label: TextView? = null
    private var bgDrawable: GradientDrawable? = null

    // Last values pushed to the view, so refresh() only touches what changed.
    private var lastCount = -1
    private var lastOver: Boolean? = null
    private var lastBgAlpha = -1

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun show() {
        val existing = label
        if (existing != null) {
            // The system can silently drop an overlay window. If ours is no longer attached (and was not just
            // added), forget it and add a fresh one instead of showing nothing until the service restarts.
            if (existing.isAttachedToWindow || SystemClock.uptimeMillis() - addedAt < REATTACH_GRACE_MS) {
                refresh(); return
            }
            hide()
        }
        val dm = service.resources.displayMetrics
        params.x = AppSettings.overlayX.coerceIn(0, (dm.widthPixels - (60 * density).toInt()).coerceAtLeast(0))
        params.y = AppSettings.overlayY.coerceIn(0, (dm.heightPixels - (40 * density).toInt()).coerceAtLeast(0))
        val view = createView()
        try {
            wm.addView(view, params)
            label = view
            addedAt = SystemClock.uptimeMillis()
            lastCount = -1; lastOver = null; lastBgAlpha = -1
            refresh()
        } catch (_: Exception) {
            label = null
            bgDrawable = null
        }
    }

    fun hide() {
        val view = label ?: return
        try { wm.removeView(view) } catch (_: Exception) { }
        label = null
        bgDrawable = null
    }

    /** Pull the counter back inside the screen after a rotation or display-size change. */
    fun keepOnScreen() {
        val view = label ?: return
        val dm = service.resources.displayMetrics
        val w = if (view.width > 0) view.width else (60 * density).toInt()
        val h = if (view.height > 0) view.height else (40 * density).toInt()
        val x = params.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        val y = params.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
        }
    }

    /** Re-reads count, limit and transparency. Text turns red once the count is above the limit. */
    fun refresh() {
        val view = label ?: return
        val count = ReelRepository.store.todayCount()
        val over = count > AppSettings.limit.value
        val bgAlpha = ((1f - AppSettings.transparency.value) * 255f).roundToInt().coerceIn(0, 255)

        if (count != lastCount) view.text = "▶ $count"
        if (over != lastOver) { view.setTextColor(if (over) OVER_LIMIT_RED else Color.WHITE); lastOver = over }
        if (bgAlpha != lastBgAlpha) { bgDrawable?.alpha = bgAlpha; lastBgAlpha = bgAlpha }

        lastCount = count
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(): TextView {
        val bg = GradientDrawable().apply {
            cornerRadius = 100 * density
            setColor(0xFF111111.toInt())
        }
        bgDrawable = bg
        return TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
            val h = (10 * density).toInt()
            val v = (5 * density).toInt()
            setPadding(h, v, h, v)
            background = bg

            var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
            setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dm = service.resources.displayMetrics
                        val maxX = (dm.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - view.height).coerceAtLeast(0)
                        val x = (startX + (e.rawX - downX).toInt()).coerceIn(0, maxX)
                        val y = (startY + (e.rawY - downY).toInt()).coerceIn(0, maxY)
                        if (x != params.x || y != params.y) {
                            params.x = x
                            params.y = y
                            try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        AppSettings.overlayX = params.x
                        AppSettings.overlayY = params.y
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private companion object {
        const val OVER_LIMIT_RED = 0xFFFF4D4D.toInt()
        const val REATTACH_GRACE_MS = 1000L
    }
}
