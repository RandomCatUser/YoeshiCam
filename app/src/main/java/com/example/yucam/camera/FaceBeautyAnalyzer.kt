package com.example.yucam.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceBeautyAnalyzer(private val onFacesDetected: (Boolean) -> Unit) : ImageAnalysis.Analyzer {

    // High performance mode, no landmarks needed if we just do a generic skin smoothing mask, 
    // but we can enable contours if we want to do face slimming/eye enlargement.
    private val realTimeOpts = FaceDetectorOptions.Builder()
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .build()

    private val detector = FaceDetection.getClient(realTimeOpts)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            detector.process(image)
                .addOnSuccessListener { faces ->
                    // Pass true if faces detected to trigger beauty shader logic 
                    // (e.g. passing uniforms to OpenGL shader to adjust smoothing intensity)
                    onFacesDetected(faces.isNotEmpty())
                }
                .addOnFailureListener {
                    // Handle failure gracefully
                }
                .addOnCompleteListener {
                    // Always close the image proxy to receive the next frame
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
