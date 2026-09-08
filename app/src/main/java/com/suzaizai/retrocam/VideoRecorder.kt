package com.suzaizai.retrocam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPU video recorder that produces H.264 + AAC MP4 with audio.
 *
 * Unlike the old CPU canvas pipeline (which re-processed every frame on the
 * CPU and produced choppy, shader-inconsistent footage), each camera frame is
 * uploaded to the GPU and pushed through the *same* vibe (warm grade / grain /
 * vignette / beauty) fragment shader the preview uses, rendered into the video
 * encoder's input Surface and hardware-encoded. Microphone audio is captured
 * on a separate thread, encoded to AAC, and muxed in with the video.
 *
 * Threading model: [start], [frame] and [stop] must all be called from the
 * *same* thread (the CameraX analysis executor) so the codec, EGL and muxer
 * stay serialised. The audio thread only produces AAC packets into a bounded
 * queue which the encoding thread drains, so the MediaMuxer is only ever
 * touched from one thread.
 */
class VideoRecorder(private val outFile: File) {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIMETYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIMETYPE_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TARGET_FPS = 30
        private const val BIT_RATE = 8_000_000
        private const val MICROS_PER_SEC = 1_000_000L

        private const val SAMPLE_RATE = 44100
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BIT_RATE = 128_000

        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FALLBACK_FRAMES = 45L

        fun publishToGallery(context: Context, file: File): Boolean {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "YOESHI_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/YoeshiCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to save video", t)
                resolver.delete(uri, null, null)
                false
            }
        }
    }

    // ---- state ----
    private var codec: MediaCodec? = null
    private var codecInputSurface: android.view.Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var haveAddedVideoTrack = false
    private var haveAddedAudioTrack = false

    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioDone = AtomicBoolean(false)
    private val audioQueue = ArrayBlockingQueue<AudioSample>(64)

    // GL state (encoding thread)
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var program = 0
    private var textureId = 0
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var rotatedWidth = 0
    private var rotatedHeight = 0
    private var pixelsBuffer: ByteBuffer? = null
    private var quadBuffer: FloatBuffer? = null

    private var startNanos = 0L
    private var frameCount = 0L
    private var vibesActive = true

    private val bufferInfo = MediaCodec.BufferInfo()

    val isRecording: Boolean
        get() = codec != null

    private class AudioSample(val data: ByteArray, val ptsUs: Long)

    /** Starts the recorder. Call from the encoder thread before the first [frame]. */
    fun start(width: Int, height: Int, rotation: Int, filterActive: Boolean) {
        if (codec != null) return

        vibesActive = filterActive

        // Decide encoded dimensions. Analysis frames arrive in sensor (landscape)
        // orientation; we record them as-is and store the rotation as metadata so
        // players display it upright.
        rotatedWidth = width
        rotatedHeight = height

        muxer = try {
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
                if (rotation != 0) {
                    try { m.setOrientationHint(rotation) } catch (_: Exception) {}
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Muxer create failed", t)
            return
        }

        startNanos = nowNanos()

        // ---- video encoder ----
        val c = try {
            MediaCodec.createEncoderByType(MIMETYPE_AVC)
        } catch (t: Throwable) {
            Log.e(TAG, "Video encoder create failed", t)
            teardown()
            return
        }
        try {
            val format = MediaFormat.createVideoFormat(MIMETYPE_AVC, rotatedWidth, rotatedHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecInputSurface = c.createInputSurface()
            c.start()
            codec = c
        } catch (t: Throwable) {
            Log.e(TAG, "Video encoder setup failed", t)
            teardown()
            return
        }

        // ---- GL setup on the encoder surface ----
        if (!setupEgl(codecInputSurface!!)) {
            Log.e(TAG, "EGL setup failed")
            teardown()
            return
        }

        // ---- audio encoder + recorder thread ----
        startAudio()

        frameCount = 0
    }

    /** Encodes one already-filtered frame (matches preview shader on the GPU). */
    fun frame(source: Bitmap) {
        val c = codec ?: return
        if (source.width != configuredWidth || source.height != configuredHeight) {
            // First frame drives the texture size.
            configureFrameSource(source.width, source.height)
        }
        val eglDisp = eglDisplay ?: return
        val eglSurf = eglSurface ?: return

        // Upload pixels as RGBA texture.
        val srcPixels = IntArray(source.width * source.height)
        source.getPixels(srcPixels, 0, source.width, 0, 0, source.width, source.height)
        val buf = pixelsBuffer ?: return
        buf.clear()
        for (p in srcPixels) {
            buf.put(((p ushr 16) and 0xFF).toByte()) // R
            buf.put(((p ushr 8) and 0xFF).toByte())  // G
            buf.put((p and 0xFF).toByte())           // B
            buf.put(((p ushr 24) and 0xFF).toByte()) // A
        }
        buf.flip()

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0,
            source.width, source.height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )

        // Render the vibe shader to the encoder surface.
        val elapsed = (nowNanos() - startNanos) / 1_000_000_000f
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTimeLoc, elapsed)
        GLES20.glUniform1f(uFilterLoc, if (vibesActive) 1f else 0f)
        GLES20.glUniform1f(uBeautyLoc, 0f)
        GLES20.glUniform2f(uTexelSizeLoc, 1f / source.width, 1f / source.height)

        val qb = quadBuffer ?: return
        qb.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, qb)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        qb.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, qb)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)

        // Timestamp the frame relative to recording start so audio/video line up.
        val framePtsUs = (nowNanos() - startNanos) / 1000L
        try {
            EGLExt.eglPresentationTimeANDROID(eglDisp, eglSurf, framePtsUs)
        } catch (_: Throwable) {}
        EGL14.eglSwapBuffers(eglDisp, eglSurf)
        frameCount++

        drainVideo(c)
        drainAudioEncoded()
    }

    /** Stops recording, drains everything and finalises the MP4. */
    fun stop() {
        audioDone.set(true)
        audioThread?.join(2000)

        val c = codec
        if (c != null) {
            try {
                // Surface-input encoders are told "no more frames" via the input
                // stream; the encoder then flushes everything and emits EOS.
                c.signalEndOfInputStream()
                drainVideo(c, timeoutUs = 30_000L, eosExpected = true)
            } catch (t: Throwable) {
                Log.e(TAG, "Final drain failed", t)
            }
        }
        drainAudioEncoded()

        releaseEgl()
        runCatching { codecInputSurface?.release() }
        runCatching { c?.stop() }
        runCatching { c?.release() }
        codec = null

        runCatching { audioCodec?.stop() }
        runCatching { audioCodec?.release() }
        audioCodec = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null
        codecInputSurface = null
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        haveAddedVideoTrack = false
        haveAddedAudioTrack = false
    }

    // ======================= GL / render =======================

    private fun setupEgl(inputSurface: android.view.Surface): Boolean {
        return try {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) {
                Log.e(TAG, "eglInitialize failed")
                return false
            }
            eglDisplay = d

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            if (!EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                Log.e(TAG, "eglChooseConfig failed")
                return false
            }
            val config = configs[0]!!

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(d, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (ctx === EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed")
                return false
            }
            eglContext = ctx

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val surf = EGL14.eglCreateWindowSurface(d, config, inputSurface, surfaceAttribs, 0)
            if (surf === EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed")
                return false
            }
            eglSurface = surf

            if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) {
                Log.e(TAG, "eglMakeCurrent failed")
                return false
            }

            program = ShaderProgram.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            uTimeLoc = GLES20.glGetUniformLocation(program, "uTime")
            uFilterLoc = GLES20.glGetUniformLocation(program, "uFilter")
            uBeautyLoc = GLES20.glGetUniformLocation(program, "uBeauty")
            uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val quad = floatArrayOf(
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
            )
            val bb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
            quadBuffer = bb.asFloatBuffer().apply { put(quad); position(0) }

            configuredWidth = 0
            configuredHeight = 0
            true
        } catch (t: Throwable) {
            Log.e(TAG, "EGL setup error", t)
            false
        }
    }

    private fun configureFrameSource(w: Int, h: Int) {
        configuredWidth = w
        configuredHeight = h
        pixelsBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
    }

    private fun releaseEgl() {
        runCatching { GLES20.glDeleteTextures(1, IntArray(1).also { it[0] = textureId }, 0) }
        val d = eglDisplay
        val s = eglSurface
        val c = eglContext
        if (d != null) {
            if (s != null) EGL14.eglDestroySurface(d, s)
            if (c != null) EGL14.eglDestroyContext(d, c)
            EGL14.eglTerminate(d)
        }
        eglDisplay = null
        eglContext = null
        eglSurface = null
    }

    private fun drainVideo(c: MediaCodec, timeoutUs: Long = DRAIN_TIMEOUT_US, eosExpected: Boolean = false) {
        val mux = muxer ?: return
        while (true) {
            val idx = c.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!haveAddedVideoTrack) {
                        videoTrack = mux.addTrack(c.outputFormat)
                        haveAddedVideoTrack = true
                        maybeStartMuxer()
                    }
                }
                idx >= 0 -> {
                    val b = c.getOutputBuffer(idx) ?: continue
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && bufferInfo.size > 0 && muxerStarted && videoTrack >= 0) {
                        b.position(bufferInfo.offset)
                        b.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(videoTrack, b, bufferInfo)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun maybeStartMuxer() {
        val mux = muxer ?: return
        if (muxerStarted) return
        // Wait until at least the video format is known (it always is). If audio
        // is unavailable or has been given enough time to produce its format,
        // start once the fallback frame budget elapses so a silent/blocked mic
        // can never stall the whole recording.
        if (!haveAddedVideoTrack) return
        val audioReady = !audioAvailable() || haveAddedAudioTrack
        val gaveUpOnAudio = frameCount >= FALLBACK_FRAMES
        if (audioReady || gaveUpOnAudio) {
            try {
                mux.start()
                muxerStarted = true
            } catch (t: Throwable) {
                Log.e(TAG, "Muxer start failed", t)
            }
        }
    }

    private fun audioAvailable(): Boolean = audioCodec != null

    // ======================= Audio =======================

    private fun startAudio() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2)
            )
            audioRecord = record

            val ac = MediaCodec.createEncoderByType(MIMETYPE_AAC)
            val format = MediaFormat.createAudioFormat(MIMETYPE_AAC, SAMPLE_RATE, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE * 2)
            }
            ac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac

            audioDone.set(false)
            val thread = Thread {
                audioLoop(record, ac)
            }
            thread.isDaemon = true
            thread.name = "YoeshiAudioEncoder"
            audioThread = thread
            thread.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Audio start failed (recording without sound)", t)
            audioCodec = null
            audioRecord = null
        }
    }

    private fun audioLoop(record: AudioRecord, ac: MediaCodec) {
        val pcmBuf = ByteArray(4096)
        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
        }

        val info = MediaCodec.BufferInfo()
        while (!audioDone.get()) {
            val read = try {
                record.read(pcmBuf, 0, pcmBuf.size)
            } catch (t: Throwable) {
                -1
            }
            if (read <= 0) {
                Thread.sleep(1)
                continue
            }

            val ptsUs = (nowNanos() - startNanos) / 1000L
            val inIndex = ac.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = ac.getInputBuffer(inIndex) ?: continue
                buf.clear()
                buf.put(pcmBuf, 0, read)
                ac.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
            }

            // Drain available encoded audio into the queue for the video thread to mux.
            while (true) {
                val outIndex = ac.dequeueOutputBuffer(info, 0L)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        audioFormatOut = ac.outputFormat
                    }
                    outIndex >= 0 -> {
                        val b = ac.getOutputBuffer(outIndex)
                        if (b == null) {
                            ac.releaseOutputBuffer(outIndex, false)
                            break
                        }
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val out = ByteArray(info.size)
                            b.position(info.offset)
                            b.get(out)
                            val ts = info.presentationTimeUs
                            if (ts >= 0) {
                                audioQueue.offer(AudioSample(out, ts))
                            }
                        }
                        ac.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break
                }
            }
        }

        // Final drain after stop signal.
        drainAudioLoop(ac)
    }

    @Volatile private var audioFormatOut: MediaFormat? = null

    private fun drainAudioLoop(ac: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = ac.dequeueOutputBuffer(info, 2000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioFormatOut = ac.outputFormat
                }
                outIndex >= 0 -> {
                    val b = ac.getOutputBuffer(outIndex)
                    if (b == null) {
                        ac.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val out = ByteArray(info.size)
                        b.position(info.offset)
                        b.get(out)
                        if (info.presentationTimeUs >= 0) {
                            audioQueue.offer(AudioSample(out, info.presentationTimeUs))
                        }
                    }
                    ac.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> break
            }
        }
    }

    /** Called on the encoder thread; writes queued audio packets to the muxer. */
    private fun drainAudioEncoded() {
        val mux = muxer ?: return
        // If no audio codec is active, there is nothing to drain.
        val ac = audioCodec ?: return
        val fmt = audioFormatOut ?: return

        // Tracks must be added before the muxer starts. If the muxer already
        // started (fallback video-only path), drop audio rather than crash.
        if (muxerStarted) return
        if (!haveAddedAudioTrack) {
            audioTrack = mux.addTrack(fmt)
            haveAddedAudioTrack = true
            maybeStartMuxer()
        }
        if (!muxerStarted) return
        if (audioTrack < 0) return

        while (true) {
            val s = audioQueue.poll() ?: break
            val bb = ByteBuffer.wrap(s.data)
            val info = MediaCodec.BufferInfo()
            info.set(0, s.data.size, s.ptsUs, 0)
            try {
                mux.writeSampleData(audioTrack, bb, info)
            } catch (t: Throwable) {
                break
            }
        }
    }

    // ======================= shaders =======================

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uTimeLoc = 0
    private var uFilterLoc = 0
    private var uBeautyLoc = 0
    private var uTexelSizeLoc = 0

    private fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()

    private val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord.xy;
        }
    """

    // Same vibe math as the preview (GLRenderer) but sampling a 2D texture
    // instead of the camera's external-OES surface, so recorded footage matches
    // what the user saw in the viewfinder (warm grade + grain + vignette).
    private val FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform float uTime;
        uniform float uFilter;
        uniform float uBeauty;
        uniform vec2 uTexelSize;

        float rand(vec2 co) {
            return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
        }

        void main() {
            vec3 color = texture2D(uTexture, vTexCoord).rgb;

            vec3 warm = color;
            warm.r = warm.r * 1.06 + 0.02;
            warm.g = warm.g * 1.02 + 0.01;
            warm.b = warm.b * 0.94;
            warm = clamp((warm - 0.5) * 1.05 + 0.5, 0.0, 1.0);

            vec3 graded = mix(color, warm, uFilter);

            vec2 centered = vTexCoord - vec2(0.5);
            float vignette = smoothstep(0.85, 0.45, length(centered));
            vec3 vignetted = mix(graded * 0.88, graded, vignette);
            vec3 finalColor = mix(graded, vignetted, uFilter);

            float grain = (rand(vTexCoord * (uTime * 120.0 + 3.0)) - 0.5) * 0.05 * uFilter;
            finalColor += vec3(grain);

            gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
        }
    """

    private fun teardown() {
        try { releaseEgl() } catch (_: Throwable) {}
        runCatching { codecInputSurface?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecInputSurface = null
        runCatching { muxer?.release() }
        muxer = null
    }
}
