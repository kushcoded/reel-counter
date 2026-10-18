package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * The tiny draggable pill. Uses TYPE_ACCESSIBILITY_OVERLAY, so no "draw over other apps"
 * permission is needed - it comes with the accessibility service.
 */
class ReelOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private var label: TextView? = null
    private var lastCount = -1

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun show() {
        if (label != null) { refresh(); return }
        params.x = AppSettings.overlayX
        params.y = AppSettings.overlayY
        val view = createView()
        try {
            wm.addView(view, params)
            label = view
            lastCount = -1
            refresh()
        } catch (_: Exception) {
            label = null
        }
    }

    fun hide() {
        val view = label ?: return
        try { wm.removeView(view) } catch (_: Exception) { }
        label = null
    }

    /** Re-reads count, goal and transparency. Text turns red once the count is above the goal. */
    fun refresh() {
        val view = label ?: return
        val count = ReelRepository.todayCount()
        view.text = "▶ $count"
        view.setTextColor(if (count > AppSettings.goal.value) OVER_GOAL_RED else Color.WHITE)
        view.alpha = 1f - AppSettings.transparency.value
        if (lastCount in 0 until count) {
            view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(90).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()
        }
        lastCount = count
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView(): TextView = TextView(service).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        val h = (10 * density).toInt()
        val v = (5 * density).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = 100 * density
            setColor(0xFF111111.toInt())
        }

        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dm = service.resources.displayMetrics
                    params.x = (startX + (e.rawX - downX).toInt()).coerceIn(0, dm.widthPixels - view.width)
                    params.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, dm.heightPixels - view.height)
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
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

    private companion object {
        const val OVER_GOAL_RED = 0xFFFF4D4D.toInt()
    }
}
