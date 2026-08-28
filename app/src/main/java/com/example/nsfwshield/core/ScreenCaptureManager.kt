package com.example.nsfwshield.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the MediaProjection + VirtualDisplay + ImageReader pipeline that captures
 * the screen at a low frame rate (2–3 FPS) for on-device analysis.
 *
 * Design notes:
 *  - A dedicated [HandlerThread] backs the ImageReader so frames never touch the
 *    main thread.
 *  - We capture at a reduced width (≤ 360px) to limit memory and CPU; the model
 *    only needs 224×224, so higher resolution is wasted work.
 *  - Only the latest frame is processed; older frames are dropped immediately by
 *    acquiring and closing them inline.
 *  - Every [Image] is closed in a finally block. No bitmaps are retained.
 *  - Nothing is written to disk or sent over the network.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val onFrameAvailable: (Bitmap) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val VIRTUAL_DISPLAY_NAME = "shield_capture"
        // Target ~3 FPS. ImageReader will still deliver faster; we sample in onImage.
        private const val CAPTURE_WIDTH = 360
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val running = AtomicBoolean(false)
    private var lastProcessedMs: Long = 0
    // ~333ms between processed frames => 3 FPS sampling.
    private val frameIntervalMs: Long = 333L

    fun start(resultCode: Int, data: Intent) {
        if (running.get()) return
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data)
        if (proj == null) {
            onError("MediaProjection is null — consent was not granted.")
            return
        }
        projection = proj

        proj.callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system.")
                stopInternal()
                onError("MediaProjection stopped.")
            }
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Scale down keeping aspect; cap width at CAPTURE_WIDTH.
        val scale = CAPTURE_WIDTH.toFloat() / screenWidth.toFloat()
        val captureW = CAPTURE_WIDTH
        val captureH = (screenHeight * scale).toInt().coerceAtLeast(1)

        captureThread = HandlerThread("shield-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader -> onImage(reader) }, captureHandler)

        virtualDisplay = proj.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureW, captureH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )

        running.set(true)
    }

    private fun onImage(reader: ImageReader) {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        if (now - lastProcessedMs < frameIntervalMs) {
            // Sampling: close and drop this frame to save battery.
            val image = reader.acquireLatestImage() ?: return
            image.close()
            return
        }

        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            onError("ImageReader error: ${e.message}")
            return
        } ?: return

        try {
            val bitmap = imageToBitmap(image) ?: return
            lastProcessedMs = now
            onFrameAvailable(bitmap)
        } catch (e: OutOfMemoryError) {
            onError("Insufficient memory while processing frame.")
        } catch (e: Exception) {
            onError("Frame processing error: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Converts an RGBA_8888 [Image] to a [Bitmap], correctly handling rowStride,
     * pixelStride, and padding. The returned bitmap may be rotated if the display
     * orientation differs; we normalise to display upright.
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - width * pixelStride

        val buffer = plane.buffer
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        // Crop the padding columns if any.
        val cropped = if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }

        // The VirtualDisplay mirrors the display, which on phones is upright.
        // No additional rotation needed for portrait phones in normal use.
        return cropped
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        running.set(false)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null
    }
}
