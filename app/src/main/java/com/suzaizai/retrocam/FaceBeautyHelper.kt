package com.suzaizai.retrocam

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit


object FaceBeautyHelper {

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    
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
