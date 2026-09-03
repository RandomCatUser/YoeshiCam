package com.example.yucam.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGLRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var surfaceTexture: SurfaceTexture? = null
    private var glSurfaceView: GLSurfaceView? = null

    private var program: Int = 0
    private var textureId: Int = 0

    // Attribute locations
    private var aPositionLocation: Int = 0
    private var aTexCoordLocation: Int = 0

    // Uniform locations
    private var uTextureLocation: Int = 0
    private var uTimeLocation: Int = 0
    private var uTexTransformLocation: Int = 0

    // Vertex buffer for full-screen quad
    private val quadVertices = floatArrayOf(
        // X, Y           U, V
        -1f, -1f,        0f, 0f,
         1f, -1f,        1f, 0f,
        -1f,  1f,        0f, 1f,
         1f,  1f,        1f, 1f,
    )
    private var vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(quadVertices).position(0) }

    // Latest camera transform matrix (handles rotation + mirroring/aspect)
    private val texMatrix = FloatArray(16)
    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private var isReady = false

    // Callback when the SurfaceTexture is ready for CameraX to use
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    /** Returns the SurfaceTexture the camera should feed frames into. */
    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    /** Set the buffer size of the camera SurfaceTexture (called when CameraX provides a Surface). */
    fun setSurfaceTextureSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun setGlSurfaceView(view: GLSurfaceView) {
        this.glSurfaceView = view
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Create an external texture for the camera preview
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Compile shaders and link program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, Shaders.VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, Shaders.Y2K_FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // Get attribute/uniform locations
        aPositionLocation = GLES20.glGetAttribLocation(program, "position")
        aTexCoordLocation = GLES20.glGetAttribLocation(program, "inputTextureCoordinate")
        uTextureLocation = GLES20.glGetUniformLocation(program, "inputImageTexture")
        uTimeLocation = GLES20.glGetUniformLocation(program, "time")
        uTexTransformLocation = GLES20.glGetUniformLocation(program, "texTransform")

        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener(this@CameraGLRenderer)
            texMatrix.copyFrom(value = identityMatrix)
        }

        isReady = true
        onSurfaceTextureAvailable?.invoke(surfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(texMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Bind the vertex data
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLocation)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLocation)

        // Activate and bind the camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTextureLocation, 0)

        // Apply the camera transform matrix for correct orientation/mirroring
        GLES20.glUniformMatrix4fv(uTexTransformLocation, 1, false, texMatrix, 0)

        // Pass time uniform for animated noise
        GLES20.glUniform1f(uTimeLocation, (System.currentTimeMillis() % 10000) / 1000f)

        // Draw the full-screen quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Disable vertex attrib arrays
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTexCoordLocation)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        // Trigger a redraw when a new frame from CameraX is available
        glSurfaceView?.requestRender()
    }

    /** Apply the CameraX preview transform so rotation/mirroring are handled correctly. */
    fun applyTransformMatrix(matrix: FloatArray) {
        texMatrix.copyFrom(matrix)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        // Check compile status
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val errorMsg = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e("CameraGLRenderer", "Shader compilation failed: $errorMsg")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}

private fun FloatArray.copyFrom(value: FloatArray) {
    value.copyInto(this, 0, 0, 16)
}