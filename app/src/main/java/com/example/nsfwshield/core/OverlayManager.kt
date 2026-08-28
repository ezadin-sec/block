package com.example.nsfwshield.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.example.nsfwshield.R

/**
 * Manages the full-screen overlay shown when NSFW content is detected.
 *
 * Uses TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW). The overlay is
 * opaque so the underlying content is not visible through it. It is shown and
 * hidden on demand by the [ProtectionService].
 */
class OverlayManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val canDrawOverlays: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(context) else true

    fun show() {
        if (overlayView != null) return // already shown
        if (!canDrawOverlays) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE
        ).apply {
            // Slight animation on appear.
            windowAnimations = android.R.style.Animation_Dialog
        }

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_blocked, null)
        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            // If adding the view fails (e.g. permission revoked at runtime), clear.
            overlayView = null
        }
    }

    fun hide() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // view may already have been removed
        }
        overlayView = null
    }

    fun isShowing(): Boolean = overlayView != null
}
