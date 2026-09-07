package com.suzaizai.retrocam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

/**
 * Bakes the same look the GL preview shows (beauty smoothing + warm Y2K
 * grade + vignette + grain + border/timestamp) into the full-resolution
 * still captured by CameraX's ImageCapture use case, then writes it to the
 * public gallery via MediaStore (scoped-storage safe, no legacy storage
 * permission needed on API 29+).
 *
 * Runs entirely off the UI thread - call it from a background executor.
 */
object PhotoProcessor {

    fun process(
        source: Bitmap,
        beautyIntensity: Float,
        filterIntensity: Float,
        faceRects: List<RectF>
    ): Bitmap {
        val w = source.width
        val h = source.height
        var working = source.copy(Bitmap.Config.ARGB_8888, true)

        if (beautyIntensity > 0.01f) {
            working = applyBeauty(working, beautyIntensity, faceRects)
        }
        if (filterIntensity > 0.01f) {
            applyRetroGrade(working, filterIntensity)
            applyGrain(working, filterIntensity)
        }
        drawBorderAndTimestamp(working)
        return working
    }

    // ---- Beauty: smooth skin, biased towards detected face regions ----

    private fun applyBeauty(bitmap: Bitmap, intensity: Float, faceRects: List<RectF>): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        val smoothed = boxBlurDownscaled(bitmap, downscaleFactor = 8) // whole-frame smoothing
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        val baseAlpha = (intensity * 60).toInt() // gentle everywhere
        maskCanvas.drawColor(Color.argb(baseAlpha, 0, 0, 0))

        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val strongAlpha = (intensity * 220).toInt().coerceIn(0, 255)
        for (rect in faceRects) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val radius = max(rect.width(), rect.height()) * 0.9f
            facePaint.shader = RadialGradient(
                cx, cy, radius,
                Color.argb(strongAlpha, 0, 0, 0),
                Color.argb(0, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
            maskCanvas.drawCircle(cx, cy, radius, facePaint)
        }

        val maskedSmoothed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mc = Canvas(maskedSmoothed)
        mc.drawBitmap(smoothed, 0f, 0f, null)
        val dstIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        mc.drawBitmap(mask, 0f, 0f, dstIn)

        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(maskedSmoothed, 0f, 0f, null)
        return out
    }

    /** Fast approximate blur: shrink, box-blur-by-scaling, grow back. */
    private fun boxBlurDownscaled(bitmap: Bitmap, downscaleFactor: Int): Bitmap {
        val w = max(1, bitmap.width / downscaleFactor)
        val h = max(1, bitmap.height / downscaleFactor)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        return Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
    }

    // ---- Retro Y2K digicam color grade + vignette ----

    private fun applyRetroGrade(bitmap: Bitmap, intensity: Float) {
        val canvas = Canvas(bitmap)

        // Warm shift lerped between identity and the warm matrix so
        // `intensity` scales the whole effect smoothly.
        val identity = ColorMatrix()
        val warm = ColorMatrix(
            floatArrayOf(
                1.08f, 0f, 0f, 0f, 6f,
                0f, 1.02f, 0f, 0f, 2f,
                0f, 0f, 0.90f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val lerped = FloatArray(20)
        val a = identity.array
        val b = warm.array
        for (i in 0 until 20) lerped[i] = a[i] + (b[i] - a[i]) * intensity
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(lerped)
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        canvas.drawBitmap(copy, 0f, 0f, paint)

        // Vignette: darken corners with a radial gradient, alpha scaled by intensity.
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val radius = max(w, h) * 0.75f
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                Color.argb(0, 0, 0, 0),
                Color.argb((110 * intensity).toInt(), 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    private fun applyGrain(bitmap: Bitmap, intensity: Float) {
        val tileSize = 128
        val noiseTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val rnd = Random(System.nanoTime())
        val pixels = IntArray(tileSize * tileSize)
        for (i in pixels.indices) {
            val v = rnd.nextInt(256)
            pixels[i] = Color.argb(255, v, v, v)
        }
        noiseTile.setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)

        val shader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            alpha = (intensity * 22).toInt().coerceIn(0, 40)
        }
        Canvas(bitmap).drawRect(
            0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint
        )
    }

    // ---- Per-frame processing for recorded video ----
    //
    // The preview shows the same look on the GPU; here we approximate it with
    // cheap CPU passes one frame at a time so the baked video matches what the
    // viewfinder showed (whole-frame smoothing rather than face-masked, mirroring
    // the GPU shader's global box blur). Cosmetics like the border/timestamp are
    // deliberately skipped - they make sense on a still, not in moving footage.

    fun processVideoFrame(
        source: Bitmap,
        beautyIntensity: Float,
        filterIntensity: Float,
        vignette: Bitmap
    ): Bitmap {
        var working = source.copy(Bitmap.Config.ARGB_8888, true)

        if (beautyIntensity > 0.01f) {
            working = applyVideoBeauty(working, beautyIntensity)
        }
        if (filterIntensity > 0.01f) {
            applyWarmGrade(working, filterIntensity)
            applyGrain(working, filterIntensity)
        }

        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (filterIntensity * 255).toInt().coerceIn(0, 255)
        }
        Canvas(working).drawBitmap(vignette, 0f, 0f, vignettePaint)
        return working
    }

    /** Whole-frame skin smoothing: same "mix toward a box blur" the preview shader does. */
    private fun applyVideoBeauty(bitmap: Bitmap, intensity: Float): Bitmap {
        val blurred = boxBlurDownscaled(bitmap, downscaleFactor = 8)
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (intensity * 0.85f * 255).toInt().coerceIn(0, 255)
        }
        Canvas(out).drawBitmap(blurred, 0f, 0f, paint)
        blurred.recycle()
        return out
    }

    /** Warm digicam grade only (no vignette - that is drawn as a reusable overlay). */
    private fun applyWarmGrade(bitmap: Bitmap, intensity: Float) {
        val canvas = Canvas(bitmap)
        val identity = ColorMatrix()
        val warm = ColorMatrix(
            floatArrayOf(
                1.08f, 0f, 0f, 0f, 6f,
                0f, 1.02f, 0f, 0f, 2f,
                0f, 0f, 0.90f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val lerped = FloatArray(20)
        val a = identity.array
        val b = warm.array
        for (i in 0 until 20) lerped[i] = a[i] + (b[i] - a[i]) * intensity
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(lerped)
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        canvas.drawBitmap(copy, 0f, 0f, paint)
        copy.recycle()
    }

    /** Pre-built vignette gradient, reused across every frame of one recording. */
    fun createVignetteOverlay(w: Int, h: Int): Bitmap {
        val overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(overlay)
        val radius = max(w.toFloat(), h.toFloat()) * 0.75f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                Color.argb(0, 0, 0, 0),
                Color.argb(110, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return overlay
    }

    // ---- Cosmetic border + retro timestamp ----

    private fun drawBorderAndTimestamp(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val borderWidth = max(w, h) * 0.012f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = Color.parseColor("#2B2B2B")
        }
        canvas.drawRect(borderWidth / 2, borderWidth / 2, w - borderWidth / 2, h - borderWidth / 2, borderPaint)

        val dateFormat = SimpleDateFormat("dd  MM  yyyy", Locale.US)
        val text = dateFormat.format(System.currentTimeMillis())
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0B15C")
            typeface = Typeface.MONOSPACE
            textSize = h * 0.028f
            setShadowLayer(4f, 1f, 1f, Color.parseColor("#88000000"))
        }
        val padding = h * 0.03f
        val textWidth = textPaint.measureText(text)
        canvas.drawText(text, w - textWidth - padding, h - padding, textPaint)
    }

    // ---- Save to public gallery via MediaStore (scoped storage safe) ----

    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val filename = "YOESHI_${System.currentTimeMillis()}.jpg"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YoeshiCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
