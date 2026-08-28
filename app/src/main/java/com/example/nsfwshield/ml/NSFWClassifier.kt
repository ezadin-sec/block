package com.example.nsfwshield.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device NSFW image classifier backed by a real TFLite model.
 *
 * Model: nsfw.tflite (Yahoo/OpenNSFW MobileNet-v2, ~5.96 MB).
 * Verified tensor specification:
 *   - Input  : [1, 224, 224, 3] float32, channel order BGR, mean-subtracted
 *              (B - 104, G - 117, R - 123). No [0,1] or [-1,1] normalization —
 *              the mean subtraction is the preprocessing.
 *   - Output : [1, 2] float32  =>  index 0 = SFW, index 1 = NSFW.
 *
 * All inference runs locally. No network call is made. No image is persisted.
 */
class NSFWClassifier private constructor(
    private val interpreter: Interpreter,
    private val inputData: ByteBuffer,
) {
    companion object {
        private const val TAG = "NSFWClassifier"
        private const val MODEL_FILE = "nsfw.tflite"
        private const val IMG_SIZE = 224
        private const val PIXEL_CHANNELS = 3
        private const val FLOAT_BYTES = 4

        // Mean values used by the original OpenNSFW preprocessing (BGR).
        private const val MEAN_B = 104f
        private const val MEAN_G = 117f
        private const val MEAN_R = 123f

        /**
         * Loads the classifier. Throws [ModelException] with a descriptive reason
         * if the model is missing, cannot be loaded, or has an unexpected shape.
         */
        fun create(context: Context): NSFWClassifier {
            val model: MappedByteBuffer = try {
                loadModelFile(context)
            } catch (e: Exception) {
                throw ModelException("Cannot load model file '$MODEL_FILE': ${e.message}", e)
            }

            val interpreter = try {
                Interpreter(model, Interpreter.Options().setNumThreads(2))
            } catch (e: Exception) {
                throw ModelException("Cannot create TFLite interpreter: ${e.message}", e)
            }

            // --- Verify tensor shapes against the known model spec ---
            val input = interpreter.getInputTensor(0)
            val output = interpreter.getOutputTensor(0)
            val inputShape = input.shape()
            val outputShape = output.shape()

            if (inputShape.size != 4 ||
                inputShape[0] != 1 ||
                inputShape[1] != IMG_SIZE ||
                inputShape[2] != IMG_SIZE ||
                inputShape[3] != PIXEL_CHANNELS
            ) {
                interpreter.close()
                throw ModelException(
                    "Unexpected input tensor shape: ${inputShape.toList()}. " +
                        "Expected [1, $IMG_SIZE, $IMG_SIZE, $PIXEL_CHANNELS]."
                )
            }
            if (input.dataType() != org.tensorflow.lite.DataType.FLOAT32) {
                interpreter.close()
                throw ModelException("Unexpected input data type: ${input.dataType()}. Expected FLOAT32.")
            }
            if (outputShape.size != 2 || outputShape[0] != 1 || outputShape[1] != 2) {
                interpreter.close()
                throw ModelException(
                    "Unexpected output tensor shape: ${outputShape.toList()}. Expected [1, 2]."
                )
            }
            if (output.dataType() != org.tensorflow.lite.DataType.FLOAT32) {
                interpreter.close()
                throw ModelException("Unexpected output data type: ${output.dataType()}. Expected FLOAT32.")
            }

            val inputBytes = 1 * IMG_SIZE * IMG_SIZE * PIXEL_CHANNELS * FLOAT_BYTES
            val buffer = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.nativeOrder())

            return NSFWClassifier(interpreter, buffer)
        }

        private fun loadModelFile(context: Context): MappedByteBuffer {
            val fd = context.assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(fd.fileDescriptor)
            val channel = inputStream.channel
            val start = fd.startOffset
            val len = fd.declaredLength
            return channel.map(FileChannel.MapMode.READ_ONLY, start, len)
        }
    }

    /** Thrown when the model cannot be loaded or does not match the expected spec. */
    class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Raw result of a single inference: [sfw, nsfw] probabilities. */
    data class Result(val sfw: Float, val nsfw: Float) {
        /** NSFW score in [0,1]. */
        val nsfwScore: Float get() = nsfw
    }

    private val intValues = IntArray(IMG_SIZE * IMG_SIZE)
    private val outputArray = Array(1) { FloatArray(2) }

    /**
     * Runs inference on a bitmap. The bitmap is scaled to 224×224 before preprocessing.
     * This method is NOT thread-safe relative to concurrent calls on the same instance —
     * callers must serialize access (the [FrameProcessor] does so by running on a single
     * background thread).
     */
    fun classify(bitmap: Bitmap): Result {
        val scaled = scaleToModelInput(bitmap)

        inputData.rewind()
        scaled.getPixels(intValues, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        var pixel = 0
        for (i in 0 until IMG_SIZE) {
            for (j in 0 until IMG_SIZE) {
                val color = intValues[pixel++]
                val r = ((color shr 16) and 0xFF)
                val g = ((color shr 8) and 0xFF)
                val b = (color and 0xFF)
                // BGR order with mean subtraction — matches the OpenNSWW preprocessing.
                inputData.putFloat(b - MEAN_B)
                inputData.putFloat(g - MEAN_G)
                inputData.putFloat(r - MEAN_R)
            }
        }

        // Reuse the output array.
        outputArray[0][0] = 0f
        outputArray[0][1] = 0f

        try {
            interpreter.run(inputData, outputArray)
        } catch (e: Exception) {
            throw ModelException("Inference failed: ${e.message}", e)
        }

        return Result(sfw = outputArray[0][0], nsfw = outputArray[0][1])
    }

    private fun scaleToModelInput(bitmap: Bitmap): Bitmap {
        if (bitmap.width == IMG_SIZE && bitmap.height == IMG_SIZE) return bitmap
        return Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
    }

    fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing interpreter: ${e.message}")
        }
    }
}
