package com.suzaizai.retrocam

import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.suzaizai.retrocam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var processingExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var glSurface: Surface? = null

    private var lastSavedBitmap: Bitmap? = null

    @Volatile
    private var videoRecorder: VideoRecorder? = null
    @Volatile
    private var recordFile: File? = null
    private var isRecording = false
    private var recordedBeauty = 0f
    private var recordedFilter = 0f
    private var vignetteOverlay: Bitmap? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                onCameraPermissionGranted()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        processingExecutor = Executors.newSingleThreadExecutor()

        setupControls()
        startTimestampClock()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onCameraPermissionGranted()
        } else {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun onCameraPermissionGranted() {
        binding.glSurfaceView.setOnSurfaceReadyListener { surface ->
            // Fires once the GL context + SurfaceTexture exist. Safe to bind CameraX now.
            if (glSurface == null) {
                glSurface = surface
                bindCameraUseCases()
            }
        }
    }

    private fun setupControls() {
        binding.beautySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.glSurfaceView.setBeautyIntensity(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.filterSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.glSurfaceView.setFilterIntensity(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        // Apply initial values from the XML defaults.
        binding.glSurfaceView.setBeautyIntensity(binding.beautySeekBar.progress / 100f)
        binding.glSurfaceView.setFilterIntensity(binding.filterSeekBar.progress / 100f)

        binding.shutterButton.setOnClickListener { onShutterPressed() }
        binding.recordButton.setOnClickListener { toggleRecording() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.galleryThumbButton.setOnClickListener {
            Toast.makeText(this, "Open your gallery app to view saved photos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTimestampClock() {
        val format = SimpleDateFormat("dd MM yyyy", Locale.US)
        binding.timestampText.text = format.format(System.currentTimeMillis())
        binding.timestampText.postDelayed(object : Runnable {
            override fun run() {
                binding.timestampText.text = format.format(System.currentTimeMillis())
                binding.timestampText.postDelayed(this, 1000L)
            }
        }, 1000L)
    }

    private fun bindCameraUseCases() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            rebindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Binds the camera use cases. [withAnalysis] is true only while recording:
     * ImageAnalysis consumes one of the camera's limited concurrent stream
     * slots, and permanently binding three use cases makes many devices fail
     * to open the camera at all (no preview, no photos).
     */
    private fun rebindCamera(withAnalysis: Boolean = false): Boolean {
        val provider = cameraProvider ?: return false
        val surface = glSurface ?: return false
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider { request ->
                binding.glSurfaceView.setBufferSize(request.resolution.width, request.resolution.height)
                binding.glSurfaceView.renderer.frontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                request.provideSurface(
                    surface,
                    cameraExecutor
                ) { /* result: surface no longer in use by the camera; nothing to clean up here */ }
            }
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val useCases = arrayListOf<UseCase>(preview, imageCapture!!)
        if (withAnalysis) {
            if (imageAnalysis == null) {
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))
                    .build()
            }
            imageAnalysis?.setAnalyzer(cameraExecutor, VideoAnalyzer())
            useCases.add(requireNotNull(imageAnalysis))
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        return try {
            provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun switchCamera() {
        // Rebinding the camera would silently stall an active recording.
        if (isRecording) stopRecording()

        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        rebindCamera()
    }

    private fun onShutterPressed() {
        val capture = imageCapture ?: return
        flashScreen()

        // Read slider values on the UI thread; the background task only ever
        // touches plain floats/bitmaps from here on.
        val beauty = binding.beautySeekBar.progress / 100f
        val filter = binding.filterSeekBar.progress / 100f

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                processingExecutor.execute {
                    handleCapturedBitmap(bitmap, beauty, filter)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.save_failed_toast, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = image.imageInfo.rotationDegrees
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
        if (rotation != 0 || mirror) {
            val matrix = Matrix()
            if (mirror) matrix.postScale(-1f, 1f)
            matrix.postRotate(rotation.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun handleCapturedBitmap(bitmap: Bitmap, beauty: Float, filter: Float) {
        val faceRects = FaceBeautyHelper.detectFaceRectsBlocking(bitmap)
        val processed = PhotoProcessor.process(bitmap, beauty, filter, faceRects)
        val saved = PhotoProcessor.saveToGallery(this, processed)

        lastSavedBitmap = processed
        runOnUiThread {
            binding.galleryThumbButton.setImageBitmap(processed)
            Toast.makeText(
                this,
                if (saved) R.string.saved_toast else R.string.save_failed_toast,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- Video recording (CPU pipeline: analysis frames -> beauty/retro look -> H.264) ----

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (isRecording) return
        if (cameraProvider == null || glSurface == null) {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        recordedBeauty = binding.beautySeekBar.progress / 100f
        recordedFilter = binding.filterSeekBar.progress / 100f

        val file = File(cacheDir, "yoeshi_rec_${System.currentTimeMillis()}.mp4")
        recordFile = file
        videoRecorder = VideoRecorder(file)
        isRecording = true

        binding.recordButton.setBackgroundResource(R.drawable.record_stop_button)
        binding.recordButton.contentDescription = "Stop recording"

        if (!rebindCamera(withAnalysis = true)) {
            // Camera wouldn't accept the extra analysis stream - roll back.
            isRecording = false
            videoRecorder = null
            recordFile = null
            file.delete()
            binding.recordButton.setBackgroundResource(R.drawable.record_button)
            binding.recordButton.contentDescription = "Record video"
            return
        }

        Toast.makeText(this, "Recording", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false

        binding.recordButton.setBackgroundResource(R.drawable.record_button)
        binding.recordButton.contentDescription = "Record video"
        imageAnalysis?.clearAnalyzer()
        rebindCamera(withAnalysis = false)

        // Finalise + publish on the same single thread that encoded the frames.
        cameraExecutor.execute {
            try {
                videoRecorder?.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Video stop failed", t)
            } finally {
                val file = recordFile
                videoRecorder = null
                recordFile = null
                vignetteOverlay = null

                val saved = file != null && file.exists() && file.length() > 0L &&
                    VideoRecorder.publishToGallery(this@MainActivity, file)
                file?.delete()

                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (saved) R.string.video_saved_toast else R.string.save_failed_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Feeds every camera frame through the filter and into the encoder. Runs on
     * [cameraExecutor]; the recorder is lazily started on the first frame so the
     * codec dimensions exactly match what the camera delivers.
     */
    private inner class VideoAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            try {
                val recorder = videoRecorder ?: return
                val bitmap = YuvConverter.toBitmap(image)
                if (bitmap.width < 2 || bitmap.height < 2) return

                if (!recorder.isRecording) {
                    recorder.start(bitmap.width, bitmap.height, image.imageInfo.rotationDegrees)
                }
                if (vignetteOverlay == null) {
                    vignetteOverlay = PhotoProcessor.createVignetteOverlay(
                        bitmap.width, bitmap.height
                    )
                }

                val processed = PhotoProcessor.processVideoFrame(
                    bitmap,
                    recordedBeauty,
                    recordedFilter,
                    vignetteOverlay!!
                )
                recorder.frame(processed)
            } catch (t: Throwable) {
                Log.e(TAG, "Video frame failed", t)
            } finally {
                image.close()
            }
        }
    }

    private fun flashScreen() {
        val view = binding.flashOverlay
        val animator = ValueAnimator.ofFloat(0.85f, 0f)
        animator.duration = 220
        animator.addUpdateListener { view.alpha = it.animatedValue as Float }
        animator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (videoRecorder != null) {
            isRecording = false
            cameraExecutor.execute { videoRecorder?.stop() }
        }
        cameraExecutor.shutdown()
        processingExecutor.shutdown()
        binding.glSurfaceView.release()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
