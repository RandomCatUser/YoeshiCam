package com.suzaizai.retrocam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
 * Applies the final "look" to a full-resolution still before it is saved.
 *
 * Design goal: produce a clean, natural, phone-grade photo by default. The
 * retro "Su Zaizai vibe" (warm lift, soft grain, gentle vignette, date
 * stamp) is applied as a light, non-destructive grade rather than the old
 * heavy blur + harsh vignette that degraded quality. With the colour grade
 * the same "look" the preview and the GPU video recorder use, so a still
 * always matches the live viewfinder.
 *
 * Runs off the UI thread - call from a background executor.
 */
object PhotoProcessor {

    /**
     * @param vibe whether to bake in the warm/soft retro grade (photo look).
     *        When false the photo is (close to) a natural capture.
     */
    fun process(
        source: Bitmap,
        beautyIntensity: Float,
        filterIntensity: Float,
        vibe: Boolean
    ): Bitmap {
        val working = source.copy(Bitmap.Config.ARGB_8888, true)

        if (filterIntensity > 0.01f && vibe) {
            applyVibeGrade(working, filterIntensity)
        }

        if (beautyIntensity > 0.01f) {
            applyBeauty(working, beautyIntensity)
        }

        if (vibe) {
            drawBorderAndTimestamp(working)
        }
        return working
    }

    // ---- Warm / soft "vibe" grade (matches the GL AOV + video recorder) ----

    private fun applyVibeGrade(bitmap: Bitmap, intensity: Float) {
        val canvas = Canvas(bitmap)

        // Subtle warm lift and lifted shadows; low-contrast "film" feel.
        val identity = ColorMatrix()
        val warm = ColorMatrix(
            floatArrayOf(
                1.06f, 0f,    0f,    0f, 4f,   // R: +warmth
                0f,    1.02f, 0f,    0f, 2f,   // G: slight boost
                0f,    0f,    0.94f, 0f, -2f,  // B: pull a touch of blue
                0f,    0f,    0f,    1f, 0f
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

        // Soft, shallow vignette (much gentler than before).
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val radius = max(w, h) * 0.72f
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradientCompat(
                w / 2f, h / 2f, radius,
                Color.argb(0, 0, 0, 0),
                Color.argb((60 * intensity).toInt(), 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, vignettePaint)

        // Fine film grain - low alpha so it stays crisp.
        applyGrain(bitmap, intensity)
    }

    private fun applyGrain(bitmap: Bitmap, intensity: Float) {
        val tileSize = 96
        val noiseTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val rnd = Random(System.nanoTime())
        val pixels = IntArray(tileSize * tileSize)
        for (i in pixels.indices) {
            val v = 120 + rnd.nextInt(30)
            pixels[i] = Color.argb(255, v, v, v)
        }
        noiseTile.setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)

        val shader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            alpha = (intensity * 12).toInt().coerceIn(0, 22)
        }
        Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
    }

    // ---- Additional optional edge-preserving polish ----

    /**
     * Very light unsharp-mask style sharpening to bring back crispness lost
     * by the camera's default softening, without amplifying noise.
     */
    fun sharpen(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val matrix = ColorMatrix(
            floatArrayOf(
                1.0f, 0f,   0f,   0f, 0f,
                1.0f, 0f,   0f,   0f, 0f,
                1.0f, 0f,   0f,   0f, 0f,
                0f,   0f,   0f,   1f, 0f
            )
        )
        // Cheap convolution via canvas offset trick; kept mild ~10%.
        val boost = 0.12f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            alpha = (boost * 255).toInt()
        }
        val canvas = Canvas(out)
        canvas.drawBitmap(source, 1f, 0f, paint)
        canvas.drawBitmap(source, -1f, 0f, paint)
        canvas.drawBitmap(source, 0f, 1f, paint)
        canvas.drawBitmap(source, 0f, -1f, paint)
        return out
    }

    // ---- Beauty smoothing (optional, subtle) ----

    private fun applyBeauty(bitmap: Bitmap, intensity: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val small = Bitmap.createScaledBitmap(
            bitmap, max(1, w / 6), max(1, h / 6), true
        )
        val blurred = Bitmap.createScaledBitmap(small, w, h, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (intensity * 50).toInt().coerceIn(0, 90)
        }
        Canvas(bitmap).drawBitmap(blurred, 0f, 0f, paint)
        small.recycle()
        blurred.recycle()
    }

    /** Radial gradient helper so the source file doesn't need the full import at every call site. */
    private fun RadialGradientCompat(
        cx: Float, cy: Float, radius: Float,
        start: Int, end: Int, tile: Shader.TileMode
    ) = android.graphics.RadialGradient(cx, cy, radius, start, end, tile)

    // ---- Cosmetic border + retro timestamp (only when vibe is on) ----

    private fun drawBorderAndTimestamp(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val borderWidth = max(w, h) * 0.010f
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
            textSize = h * 0.026f
            setShadowLayer(4f, 1f, 1f, Color.parseColor("#88000000"))
        }
        val padding = h * 0.03f
        val textWidth = textPaint.measureText(text)
        canvas.drawText(text, w - textWidth - padding, h - padding, textPaint)
    }

    fun applyRotation(source: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        if (rotationDegrees == 0 && !mirror) return source
        val matrix = Matrix()
        if (mirror) matrix.postScale(-1f, 1f)
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
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
