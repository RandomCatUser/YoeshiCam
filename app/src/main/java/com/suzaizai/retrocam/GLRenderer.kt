package com.suzaizai.retrocam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the live camera feed (delivered via a SurfaceTexture/external OES
 * texture, as produced by CameraX's Preview use case) through a single
 * fragment shader that applies:
 *   - warm Y2K digicam color grading
 *   - vignette
 *   - animated film grain
 *   - a cheap real-time "beauty" smoothing blend (multi-tap blur)
 *
 * All effects run on the GPU so the preview stays smooth even on modest
 * devices. Sliders (0f..1f) are updated from the UI thread and read on the
 * GL thread each frame - safe here because they're just floats written
 * atomically (Volatile).
 */
class GLRenderer(
    private val onSurfaceReady: (Surface) -> Unit
) : android.opengl.GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var beautyIntensity: Float = 0.4f
    @Volatile var filterIntensity: Float = 0.8f
    @Volatile var frontCamera: Boolean = false

    private var program = 0
    private var oesTextureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private var surface: Surface? = null

    private val stMatrix = FloatArray(16)
    private var frameAvailable = false
    private val frameLock = Object()

    private var startTimeNanos = 0L

    // Full-screen quad: position (x, y) + tex coords (u, v)
    private val quadCoords = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f
    )
    private lateinit var quadBuffer: FloatBuffer

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureMatrixLoc = 0
    private var uTextureLoc = 0
    private var uTimeLoc = 0
    private var uBeautyLoc = 0
    private var uFilterLoc = 0
    private var uTexelSizeLoc = 0

    private var viewportWidth = 1
    private var viewportHeight = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        oesTextureId = createOesTexture()
        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setOnFrameAvailableListener(this)
        surface = Surface(surfaceTexture)
        onSurfaceReady(surface!!)

        program = ShaderProgram.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureMatrixLoc = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
        uBeautyLoc = GLES20.glGetUniformLocation(program, "uBeauty")
        uFilterLoc = GLES20.glGetUniformLocation(program, "uFilter")
        uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")

        val bb = ByteBuffer.allocateDirect(quadCoords.size * 4).order(ByteOrder.nativeOrder())
        quadBuffer = bb.asFloatBuffer().apply {
            put(quadCoords)
            position(0)
        }

        startTimeNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(frameLock) {
            if (frameAvailable) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(stMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTextureMatrixLoc, 1, false, stMatrix, 0)

        val elapsed = (System.nanoTime() - startTimeNanos) / 1_000_000_000f
        GLES20.glUniform1f(uTimeLoc, elapsed)
        GLES20.glUniform1f(uBeautyLoc, beautyIntensity)
        GLES20.glUniform1f(uFilterLoc, filterIntensity)
        GLES20.glUniform2f(uTexelSizeLoc, 1f / viewportWidth, 1f / viewportHeight)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(frameLock) {
            frameAvailable = true
        }
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    fun release() {
        surface?.release()
        if (::surfaceTexture.isInitialized) surfaceTexture.release()
    }

    /** Must be called (from CameraX's SurfaceRequest) before frames start flowing. */
    fun setBufferSize(width: Int, height: Int) {
        if (::surfaceTexture.isInitialized) {
            surfaceTexture.setDefaultBufferSize(width, height)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * aTexCoord).xy;
            }
        """

        // Single fragment shader doing beauty-blur -> warm grade -> vignette -> grain.
        // Kept as one pass (no extra framebuffers) so it stays cheap on low-end GPUs.
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uTime;
            uniform float uBeauty;   // 0..1 beauty smoothing intensity
            uniform float uFilter;   // 0..1 retro filter intensity
            uniform vec2 uTexelSize;

            float rand(vec2 co) {
                return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec3 sampleBlurred(vec2 uv) {
                // Cheap 9-tap box blur used as a stand-in for a bilateral
                // "skin smoothing" filter - fast enough for real-time preview.
                vec3 sum = vec3(0.0);
                float total = 0.0;
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        vec2 offset = vec2(float(x), float(y)) * uTexelSize * 2.5;
                        sum += texture2D(uTexture, uv + offset).rgb;
                        total += 1.0;
                    }
                }
                return sum / total;
            }

            void main() {
                vec3 original = texture2D(uTexture, vTexCoord).rgb;
                vec3 blurred = sampleBlurred(vTexCoord);

                // Beauty smoothing: blend towards the blurred version. Keeping
                // some of the original preserves detail (eyes/hair/edges) so
                // it doesn't look plastic even at higher intensity.
                vec3 color = mix(original, blurred, uBeauty * 0.85);

                // Slight brightening that scales with beauty amount.
                color += vec3(0.03, 0.02, 0.0) * uBeauty;

                // --- Retro Y2K digicam color grade ---
                vec3 warm = color;
                warm.r = warm.r * 1.08 + 0.02;
                warm.g = warm.g * 1.02 + 0.01;
                warm.b = warm.b * 0.90;
                // gentle contrast / lifted-black curve typical of old CCD sensors
                warm = clamp((warm - 0.5) * 1.08 + 0.5, 0.0, 1.0);

                vec3 graded = mix(color, warm, uFilter);

                // Vignette
                vec2 centered = vTexCoord - vec2(0.5);
                float vignette = smoothstep(0.85, 0.35, length(centered));
                vec3 vignetted = mix(graded * 0.75, graded, vignette);
                vec3 finalColor = mix(graded, vignetted, uFilter);

                // Animated film grain
                float grain = (rand(vTexCoord * uTime * 100.0) - 0.5) * 0.08 * uFilter;
                finalColor += vec3(grain);

                gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
            }
        """
    }
}
