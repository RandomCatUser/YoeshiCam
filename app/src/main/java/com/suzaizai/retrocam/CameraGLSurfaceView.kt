package com.suzaizai.retrocam

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface

/**
 * Thin GLSurfaceView wrapper that owns a [GLRenderer] and exposes the
 * camera-facing [Surface] once GL setup has completed, plus pass-throughs
 * for the beauty/filter sliders.
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var onSurfaceReady: ((Surface) -> Unit)? = null
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

    fun setBeautyIntensity(value: Float) {
        renderer.beautyIntensity = value.coerceIn(0f, 1f)
    }

    fun setFilterIntensity(value: Float) {
        renderer.filterIntensity = value.coerceIn(0f, 1f)
    }

    fun setBufferSize(width: Int, height: Int) {
        renderer.setBufferSize(width, height)
    }

    override fun onPause() {
        super.onPause()
    }

    fun release() {
        renderer.release()
    }
}
