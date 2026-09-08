package com.suzaizai.retrocam

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface

/**
 * Thin GLSurfaceView wrapper that owns a [GLRenderer] and exposes the
 * camera-facing [Surface] once GL setup has completed, plus pass-throughs
 * for the beauty/filter values and a tap-to-focus callback.
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var onSurfaceReady: ((Surface) -> Unit)? = null
    private var onTap: ((Float, Float) -> Unit)? = null
    lateinit var renderer: GLRenderer
        private set

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        renderer = GLRenderer { surface ->
            // Called on the GL thread; hop back to main for CameraX binding.
            post { onSurfaceReady?.invoke(surface) }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setOnSurfaceReadyListener(listener: (Surface) -> Unit) {
        onSurfaceReady = listener
    }

    /** Register a tap handler; receives normalized view coords (0..1, top-left origin). */
    fun setOnTapListener(listener: (Float, Float) -> Unit) {
        onTap = listener
    }

    fun setBeautyIntensity(value: Float) {
        renderer.beautyIntensity = value.coerceIn(0f, 1f)
    }

    fun setFilterIntensity(value: Float) {
        renderer.filterIntensity = value.coerceIn(0f, 1f)
    }

    fun setBufferSize(width: Int, height: Int) {
        renderer.setBufferSize(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x / width
            val y = event.y / height
            onTap?.invoke(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
        return true
    }

    fun release() {
        renderer.release()
    }
}