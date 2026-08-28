package com.example.nsfwshield.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.example.nsfwshield.ml.NSFWClassifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges captured frames to the classifier and decision engine.
 *
 * Runs entirely on a dedicated background thread ("shield-inference"). Only the
 * latest frame is processed; if a new frame arrives while one is being classified,
 * the new one is queued and the stale one is dropped. Bitmaps are recycled after
 * use. Nothing leaves the device.
 */
class FrameProcessor(
    private val classifier: NSFWClassifier,
    private val decisionEngine: DecisionEngine,
    private val onResult: (score: Float, blocked: Boolean, inferenceMs: Long) -> Unit,
) {
    companion object { private const val TAG = "FrameProcessor" }

    private val inferenceThread = HandlerThread("shield-inference").also { it.start() }
    private val inferenceHandler = Handler(inferenceThread.looper)
    private val pending = AtomicBoolean(false)
    private var pendingBitmap: Bitmap? = null
    private var framesAnalyzed: Long = 0

    fun submit(bitmap: Bitmap) {
        // Keep only the latest bitmap; recycle the previous pending one.
        val old = pendingBitmap
        pendingBitmap = bitmap
        pending.set(true)
        if (old != null && old !== bitmap) {
            old.recycle()
        }
        inferenceHandler.removeCallbacks(processRunnable)
        inferenceHandler.post(processRunnable)
    }

    private val processRunnable = Runnable {
        if (!pending.get()) return@Runnable
        pending.set(false)
        val bitmap = pendingBitmap ?: return@Runnable
        pendingBitmap = null

        val start = SystemClock.uptimeMillis()
        val result = try {
            classifier.classify(bitmap)
        } catch (e: NSFWClassifier.ModelException) {
            Log.e(TAG, "Classification failed: ${e.message}")
            bitmap.recycle()
            onResult(-1f, false, 0L)
            return@Runnable
        }
        val inferenceMs = SystemClock.uptimeMillis() - start
        bitmap.recycle()

        framesAnalyzed++
        decisionEngine.submit(result.nsfwScore, object : DecisionEngine.Callback {
            override fun onBlock(score: Float) = onResult(score, true, inferenceMs)
            override fun onUnblock() = onResult(result.nsfwScore, false, inferenceMs)
        })
        // If no transition happened, still report the score for diagnostics.
        if (!decisionEngine.isBlocked()) {
            onResult(result.nsfwScore, false, inferenceMs)
        }
    }

    fun framesAnalyzed(): Long = framesAnalyzed

    fun stop() {
        inferenceHandler.removeCallbacks(processRunnable)
        pendingBitmap?.recycle()
        pendingBitmap = null
        inferenceThread.quitSafely()
    }
}
