package com.suzaizai.retrocam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Encodes a stream of ARGB frames into an H.264 MP4 using a MediaCodec
 * encoder + MediaMuxer. Frames are handed in as plain bitmaps (already
 * colour-graded/beauty-processed by the caller) and repacked here into
 * whichever YUV layout the device encoder actually accepts (I420 or NV12,
 * probed from the codec's reported capabilities).
 *
 * Single-threaded, blocking codec calls - feed [frame] from exactly one
 * thread (the CameraX analysis executor). Start/stop must also happen on
 * that same thread so the codec state machine stays serialised.
 */
class VideoRecorder(private val outFile: File) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false

    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var yuvOutput = ByteArray(0)
    private var frameIndex = 0L

    private val bufferInfo = MediaCodec.BufferInfo()

    val isRecording: Boolean
        get() = codec != null

    /**
     * Sets up the encoder. Must be called before the first [frame].
     *
     * @param rotation 0/90/180/270 rotation hint from the camera - stored as
     *        MP4 metadata via MediaMuxer so portrait clips play upright.
     */
    fun start(width: Int, height: Int, rotation: Int) {
        if (codec != null) return

        configuredWidth = width
        configuredHeight = height
        yuvOutput = ByteArray(width * height * 3 / 2)

        val c = MediaCodec.createEncoderByType(MIMETYPE_AVC)
        val format = MediaFormat.createVideoFormat(MIMETYPE_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormatFor(c))
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()

        val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) {
            try {
                m.setOrientationHint(rotation)
            } catch (_: Exception) {
                // Orientation hint is decorative only; never fail the encode over it.
            }
        }

        codec = c
        muxer = m
        trackIndex = -1
        muxerStarted = false
        frameIndex = 0L
    }

    /** Encodes one already-filtered frame. Drops it if dimensions mismatch the recorder. */
    fun frame(source: Bitmap) {
        val c = codec ?: return
        if (source.width != configuredWidth || source.height != configuredHeight) return

        argbToYuv(source, yuvOutput)

        val ptsUs = frameIndex * MICROS_PER_SEC / FPS
        frameIndex++

        val inIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inIndex >= 0) {
            val inBuf = c.getInputBuffer(inIndex) ?: return
            inBuf.clear()
            inBuf.put(yuvOutput)
            c.queueInputBuffer(inIndex, 0, yuvOutput.size, ptsUs, 0)
        }
        drain(false)
    }

    /** Queues end-of-stream, drains all remaining encoded output, tears down codec + muxer. */
    fun stop() {
        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inIndex >= 0) {
                val inBuf = c.getInputBuffer(inIndex)
                inBuf?.clear()
                c.queueInputBuffer(
                    inIndex, 0, 0, frameIndex * MICROS_PER_SEC / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(true)
        } catch (t: Throwable) {
            Log.e(TAG, "Error finalising video", t)
        } finally {
            // The shutdown order matters: stop codec before muxer.
            runCatching { c.stop() }
            c.release()
            codec = null
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            muxer?.release()
            muxer = null
            muxerStarted = false
            trackIndex = -1
        }
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    startMuxerIfNeeded(c)
                }
                outIndex >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIndex) ?: continue
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        startMuxerIfNeeded(c)
                        val mux = muxer ?: continue
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun startMuxerIfNeeded(c: MediaCodec) {
        if (muxerStarted) return
        val mux = muxer ?: return
        trackIndex = mux.addTrack(c.outputFormat)
        mux.start()
        muxerStarted = true
    }

    // ---- ARGB -> YUV conversion (auto-detects planar vs semi-planar) ----

    private fun argbToYuv(src: Bitmap, out: ByteArray) {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val total = w * h
        val halfW = w / 2
        val halfH = h / 2
        val chromaSize = halfW * halfH
        val chromaBase = total

        // Full-res luma plane.
        for (i in 0 until total) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            out[i] = y.toByte()
        }

        val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var idx = chromaBase
        for (row in 0 until halfH) {
            val srcRow = row * 2 * w
            for (col in 0 until halfW) {
                val p = pixels[srcRow + col * 2]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                if (semiPlanar) {
                    out[idx++] = u.toByte()
                    out[idx++] = v.toByte()
                } else {
                    out[idx] = u.toByte()
                    out[idx + chromaSize] = v.toByte()
                    idx++
                }
            }
        }
    }

    private fun colorFormatFor(c: MediaCodec): Int {
        return try {
            val formats = c.codecInfo.getCapabilitiesForType(MIMETYPE_AVC).colorFormats
            when {
                formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            }
        } catch (t: Throwable) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }
    }

    // ---- Saving the finished clip to the public gallery ----

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIMETYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FPS = 24
        private const val BIT_RATE = 5_000_000
        private const val MICROS_PER_SEC = 1_000_000L
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val DRAIN_TIMEOUT_US = 10_000L

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
}