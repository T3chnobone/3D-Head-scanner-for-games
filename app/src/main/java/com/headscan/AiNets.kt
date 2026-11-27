package com.headscan

import android.content.Context
import com.headscan.core.AiModels
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Connects the bundled TensorFlow Lite models to [AiModels]. Each model is loaded the first time it's used
 * (they are only needed for the AI post-process options), and runs on the phone's CPU, offline.
 *
 * - realesr-general-x4v3 / realesr-general-wdn-x4v3: Real-ESRGAN by Xintao Wang et al., BSD-3-Clause.
 * - selfie_multiclass_256x256: MediaPipe, Apache-2.0.
 */
object AiNets {
    private class Net(private val context: Context, private val asset: String, inSize: Int, private val outFloats: Int) {
        private var interpreter: Interpreter? = null
        private val input = ByteBuffer.allocateDirect(inSize * 4).order(ByteOrder.nativeOrder())
        private val output = ByteBuffer.allocateDirect(outFloats * 4).order(ByteOrder.nativeOrder())

        @Synchronized
        fun run(data: FloatArray): FloatArray {
            val it = interpreter ?: Interpreter(loadAsset(), Interpreter.Options().setNumThreads(4)).also { interpreter = it }
            input.rewind(); input.asFloatBuffer().put(data)
            output.rewind()
            it.run(input, output)
            output.rewind()
            val out = FloatArray(outFloats)
            output.asFloatBuffer().get(out)
            return out
        }

        private fun loadAsset(): ByteBuffer {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        val sharpen = Net(app, "realesr-general-x4v3.tflite", 64 * 64 * 3, 256 * 256 * 3)
        val denoise = Net(app, "realesr-general-wdn-x4v3.tflite", 64 * 64 * 3, 256 * 256 * 3)
        val segment = Net(app, "selfie_multiclass_256x256.tflite", 256 * 256 * 3, 256 * 256 * 6)
        AiModels.sharpen = sharpen::run
        AiModels.denoise = denoise::run
        AiModels.segment = segment::run
    }
}
