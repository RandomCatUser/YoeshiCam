package com.suzaizai.retrocam

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

/**
 * Converts a CameraX YUV_420_888 [ImageProxy] into an ARGB [Bitmap].
 *
 * Handles per-plane rowStride/pixelStride explicitly so it works across the
 * range of vendor camera HALs (not just tightly-packed buffers), which the
 * vanilla `YuvImage` path can't guarantee.
 */
object YuvConverter {

    fun toBitmap(image: ImageProxy): Bitmap {
        if (image.format != ImageFormat.YUV_420_888) {
            return Bitmap.createBitmap(
                maxOf(1, image.width), maxOf(1, image.height), Bitmap.Config.ARGB_8888
            )
        }

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val halfW = (width + 1) / 2
        val halfH = (height + 1) / 2
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val yRowBase = row * yRowStride
            val uvRow = (row shr 1).coerceAtMost(halfH - 1)
            val uvRowBaseU = uvRow * uRowStride
            val uvRowBaseV = uvRow * vRowStride
            val outRow = row * width

            for (col in 0 until width) {
                val y = yBuf.get(yRowBase + col * yPixelStride) and 0xFF
                val uvCol = (col shr 1).coerceAtMost(halfW - 1)
                val u = (uBuf.get(uvRowBaseU + uvCol * uPixelStride) and 0xFF) - 128
                val v = (vBuf.get(uvRowBaseV + uvCol * vPixelStride) and 0xFF) - 128

                val r = (y + ((1436 * v) shr 10)).coerceIn(0, 255)
                val g = (y - ((352 * u) shr 10) - ((731 * v) shr 10)).coerceIn(0, 255)
                val b = (y + ((1814 * u) shr 10)).coerceIn(0, 255)

                pixels[outRow + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}