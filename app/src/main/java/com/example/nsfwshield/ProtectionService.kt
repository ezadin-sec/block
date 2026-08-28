package com.example.nsfwshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nsfwshield.core.AppDetector
import com.example.nsfwshield.core.DecisionEngine
import com.example.nsfwshield.core.FrameProcessor
import com.example.nsfwshield.core.OverlayManager
import com.example.nsfwshield.core.ScreenCaptureManager
import com.example.nsfwshield.ml.NSFWClassifier

/**
 * Foreground service that performs on-device screen capture + NSFW detection.
 *
 * Lifecycle:
 *  START with resultCode + Intent (from MainActivity after user consent)
 *   -> creates notification channel + foreground notification (mediaProjection type)
 *   -> starts ScreenCaptureManager
 *   -> each frame -> FrameProcessor -> DecisionEngine -> OverlayManager
 *  STOP / onDestroy
 *   -> tears down capture, inference, overlay; releases all resources.
 *
 * No background hacks. No network. No persisted images.
 */
class ProtectionService : Service() {

    companion object {
        const val CHANNEL_ID = "shield_protection"
        const val NOTIF_ID = 1001
        const val BOOT_NOTIF_ID = 1002

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_START = "com.example.nsfwshield.START"
        const val ACTION_STOP = "com.example.nsfwshield.STOP"

        // Diagnostics, updated on the inference thread and read from the UI.
        @Volatile var framesAnalyzed: Long = 0
            private set
        @Volatile var lastInferenceMs: Long = 0
            private set
        @Volatile var lastScore: Float = -1f
            private set
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var classifierStatus: String = ""
            private set
        @Volatile var foregroundApp: String? = null
            private set

        private const val TAG = "ProtectionService"
    }

    private var capture: ScreenCaptureManager? = null
    private var processor: FrameProcessor? = null
    private var classifier: NSFWClassifier? = null
    private var decision: DecisionEngine? = null
    private val overlay by lazy { OverlayManager(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProtection()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null) {
                    Log.e(TAG, "No result data in start intent.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProtection(resultCode, data)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startProtection(resultCode: Int, data: Intent) {
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        // Load classifier
        classifier = try {
            val c = NSFWClassifier.create(this)
            classifierStatus = "Loaded"
            c
        } catch (e: NSFWClassifier.ModelException) {
            classifierStatus = "Error: ${e.message}"
            Log.e(TAG, "Classifier load failed: ${e.message}", e)
            stopSelf()
            return
        }

        val sensitivity = Prefs.sensitivity(this)
        decision = DecisionEngine(sensitivity)

        processor = FrameProcessor(
            classifier = classifier!!,
            decisionEngine = decision!!,
            onResult = { score, blocked, inferenceMs ->
                framesAnalyzed = processor?.framesAnalyzed() ?: 0
                lastInferenceMs = inferenceMs
                lastScore = score
                foregroundApp = AppDetector.foregroundPackage(this)
                if (blocked) overlay.showBlock() else overlay.hideBlock()
            }
        )

        capture = ScreenCaptureManager(
            context = this,
            onFrameAvailable = { bitmap -> processor?.submit(bitmap) },
            onError = { msg ->
                Log.e(TAG, "Capture error: $msg")
                classifierStatus = "Capture error: $msg"
            }
        )
        capture?.start(resultCode, data)

        Prefs.setProtectionOn(this, true)
        isRunning = true
    }

    private fun stopProtection() {
        isRunning = false
        try { capture?.stop() } catch (_: Exception) {}
        capture = null
        try { processor?.stop() } catch (_: Exception) {}
        processor = null
        try { classifier?.close() } catch (_: Exception) {}
        classifier = null
        decision?.reset()
        decision = null
        overlay.hideBlock()
        Prefs.setProtectionOn(this, false)
        framesAnalyzed = 0
        lastInferenceMs = 0
        lastScore = -1f
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProtection()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
