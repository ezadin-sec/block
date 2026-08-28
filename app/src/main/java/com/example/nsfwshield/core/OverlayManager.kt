package com.example.nsfwshield.core

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.example.nsfwshield.R

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    fun showBlock() {
        if (isShowing) return
        
        // 1. إظهار الشاشة السوداء الصارمة
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_blocked, null)
        windowManager.addView(overlayView, params)
        isShowing = true

        // 2. الطرد الفوري والإجباري إلى الشاشة الرئيسية للهاتف (Home Screen)
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }

    fun hideBlock() {
        if (!isShowing) return
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
        isShowing = false
    }
}
