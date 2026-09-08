package com.suzaizai.retrocam

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the live camera feed (delivered via a SurfaceTexture/external OES
 * texture, as produced by CameraX's Preview use case) through a single
 * fragment shader that applies the "Su Zaizai vibe":
 *   - warm Y2K color grading
 *   - gentle vignette
 *   - subtle animated film grain
 *
 * The feed is rendered with an **aspect-fit (center crop)** mapping instead
 * of blindly stretching the landscape sensor image into the portrait view, so
 * the preview looks like a normal phone camera.
 *
 * All effects run on the GPU so the preview stays smooth even on modest
 * devices. Slider/toggle values (0f..1f) are updated from the UI thread and
 * read on the GL thread each frame - safe here because they're just floats
 * written atomically (Volatile).
 */
class GLRenderer(
    private val onSurfaceReady: (Surface) -> Unit
) : android.opengl.GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile var beautyIntensity: Float = 0f
    @Volatile var filterIntensity: Float = 1f
    @Volatile var frontCamera: Boolean = false

    private var program = 0
    private var oesTextureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private var surface: Surface? = null

    private val stMatrix = FloatArray(16)
    private var frameAvailable = false
    private val frameLock = Object()

    private var startTimeNanos = 0L

    // Preview surface size (set by CameraX via request.resolution - in sensor
    // orientation, landscape). Stored so the aspect-fit crop can be computed.
    @Volatile var previewBufferWidth: Int = 1920
    @Volatile var previewBufferHeight: Int = 1080
    private var viewportWidth = 1
    private var viewportHeight = 1

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
    private var uCropLoc = 0

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
        uCropLoc = GLES20.glGetUniformLocation(program, "uCrop")

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

        // Combine the camera transform with the aspect-fit crop. The crop is
        // computed in buffer (sensor) UV space: for a landscape buffer shown on
        // a portrait view the extra wide lens area is cropped off either side
        // (vCrop < 1 in buffer space, which - after the 90-degree texture
        // matrix rotation - removes the left/right edges of the upright image).
        val crop = computeCrop()
        GLES20.glUniform2f(uCropLoc, crop[0], crop[1])

        val elapsed = (System.nanoTime() - startTimeNanos) / 1_000_000_000f
        GLES20.glUniform1f(uTimeLoc, elapsed)
        GLES20.glUniform1f(uBeautyLoc, beautyIntensity)
        GLES20.glUniform1f(uFilterLoc, filterIntensity)
        GLES20.glUniform2f(uTexelSizeLoc, 1f / viewportWidth, 1f / viewportHeight)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    /**
     * Returns the aspect-fit crop factors in buffer UV space.
     * - [0] U-scale, [1] V-scale. Values < 1 mean "keep only that central
     *   fraction" along that axis so the image always fills the view.
     */
    fun computeCrop(): FloatArray {
        val bufW = previewBufferWidth.toFloat()
        val bufH = previewBufferHeight.toFloat()
        val viewW = viewportWidth.toFloat()
        val viewH = viewportHeight.toFloat()
        if (viewW <= 0f || viewH <= 0f || bufW <= 0f || bufH <= 0f) return floatArrayOf(1f, 1f)

        // Upright image aspect after rotating the sensor (landscape) buffer
        // into portrait (app is portrait-locked; back cam rotation 90, front 270).
        val uprightAspect = bufH / bufW
        val viewAspect = viewW / viewH

        return if (uprightAspect > viewAspect) {
            // Image is relatively wider than the view -> crop horizontally.
            // Horizontal crop in the upright image maps to V-axis in buffer UV.
            floatArrayOf(1f, viewAspect / uprightAspect)
        } else {
            // Image is relatively taller than the view -> crop vertically.
            floatArrayOf(uprightAspect / viewAspect, 1f)
        }
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
        previewBufferWidth = width
        previewBufferHeight = height
        if (::surfaceTexture.isInitialized) {
            surfaceTexture.setDefaultBufferSize(width, height)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTextureMatrix;
            uniform vec2 uCrop;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 uv = (uTextureMatrix * aTexCoord).xy;
                vTexCoord = (uv - 0.5) * uCrop + 0.5;
            }
        """

        // Single fragment shader doing the vibe: warm grade -> vignette -> grain.
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
                // some of the original preserves detail so it doesn't look
                // plastic even at higher intensity.
                vec3 color = mix(original, blurred, uBeauty * 0.85);

                // Slight brightening that scales with beauty amount.
                color += vec3(0.03, 0.02, 0.0) * uBeauty;

                // --- Warm vibe grade ---
                vec3 warm = color;
                warm.r = warm.r * 1.06 + 0.02;
                warm.g = warm.g * 1.02 + 0.01;
                warm.b = warm.b * 0.94;
                warm = clamp((warm - 0.5) * 1.05 + 0.5, 0.0, 1.0);

                vec3 graded = mix(color, warm, uFilter);

                // Vignette (gentle)
                vec2 centered = vTexCoord - vec2(0.5);
                float vignette = smoothstep(0.85, 0.45, length(centered));
                vec3 vignetted = mix(graded * 0.88, graded, vignette);
                vec3 finalColor = mix(graded, vignetted, uFilter);

                // Subtle animated film grain
                float grain = (rand(vTexCoord * (uTime * 120.0 + 3.0)) - 0.5) * 0.05 * uFilter;
                finalColor += vec3(grain);

                gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
            }
        """
    }
}