package com.suzaizai.retrocam

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around ML Kit's on-device face detector. Used only to find
 * *where* faces are in the final captured photo so the beauty smoothing can
 * be concentrated on skin rather than blurring the whole frame (hair,
 * clothing texture, background, etc).
 *
 * Runs fully on-device - no network round trip once the bundled model is
 * available, so this is safe to call from a background thread right after
 * capture without adding noticeable lag.
 */
object FaceBeautyHelper {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    /**
     * Blocking call - always invoke from a background thread (this is used
     * right after ImageCapture returns, on the photo-processing executor,
     * never on the UI/GL thread).
     */
    fun detectFaceRectsBlocking(bitmap: Bitmap, timeoutMs: Long = 1500): List<RectF> {
        return try {
            val detector = FaceDetection.getClient(options)
            val input = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(input), timeoutMs, TimeUnit.MILLISECONDS)
            detector.close()
            faces.map { RectF(it.boundingBox) }
        } catch (t: Throwable) {
            // Detection is a nice-to-have; if it fails or times out we fall
            // back to a gentler whole-frame smoothing in PhotoProcessor.
            emptyList()
        }
    }
}
