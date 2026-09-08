package com.suzaizai.retrocam

import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var glSurface: android.view.Surface? = null

    private var lastSavedBitmap: Bitmap? = null

    private var flashMode = ImageCapture.FLASH_MODE_OFF // off -> auto -> on
    private var timerSeconds = 0
    private var videoMode = false
    private var vibeEnabled = true

    // For photo/video mode switching without tearing down the camera mid-session.
    private var isRecording = false

    @Volatile
    private var videoRecorder: VideoRecorder? = null
    @Volatile
    private var recordFile: File? = null

    private var sensorRotationDegrees = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                onCameraPermissionGranted()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecordingInternal()
            } else {
                // Fall back to silent recording so video capture still works.
                Toast.makeText(this, R.string.audio_permission_denied, Toast.LENGTH_LONG).show()
                startRecordingInternal()
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
            if (glSurface == null) {
                glSurface = surface
                bindCameraUseCases()
            }
        }
    }

    // ======================= controls / UI =======================

    private fun setupControls() {
        binding.glSurfaceView.setBeautyIntensity(0f)
        binding.glSurfaceView.setFilterIntensity(if (vibeEnabled) 1f else 0f)

        binding.glSurfaceView.setOnTapListener { nx, ny ->
            handleTapToFocus(nx, ny)
        }

        binding.shutterButton.setOnClickListener { onShutterPressed() }
        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.galleryThumbButton.setOnClickListener {
            Toast.makeText(this, "Open your gallery app to view saved photos", Toast.LENGTH_SHORT).show()
        }

        // Flash cycle: Off -> Auto -> On -> Off
        binding.flashButton.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            updateFlashIcon()
            imageCapture?.flashMode = flashMode
        }

        // Timer cycle: Off -> 3s -> 10s -> Off
        binding.timerButton.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            binding.timerButton.alpha = if (timerSeconds > 0) 1f else 0.4f
            val label = "Timer: " + if (timerSeconds == 0) "off" else "${timerSeconds}s"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        // Vibe (retro filter) toggle
        binding.vibeButton.setOnClickListener {
            vibeEnabled = !vibeEnabled
            binding.vibeButton.setImageResource(if (vibeEnabled) R.drawable.ic_vibe_on else R.drawable.ic_vibe_off)
            binding.glSurfaceView.setFilterIntensity(if (vibeEnabled) 1f else 0f)
        }

        // Photo / Video mode
        binding.photoMode.setOnClickListener { setMode(video = false) }
        binding.videoMode.setOnClickListener { setMode(video = true) }

        binding.flashButton.alpha = 0.6f
        binding.timerButton.alpha = 0.4f
    }

    private fun setMode(video: Boolean) {
        if (videoMode == video) return
        if (isRecording) {
            toggleRecording()
            return
        }
        videoMode = video
        binding.photoMode.setBackgroundResource(if (!video) R.drawable.mode_active else 0)
        binding.videoMode.setBackgroundResource(if (video) R.drawable.mode_active else 0)
        binding.photoMode.setTextColor(if (!video) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        binding.videoMode.setTextColor(if (video) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun updateFlashIcon() {
        val res = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            else -> R.drawable.ic_flash_off
        }
        binding.flashButton.setImageResource(res)
        binding.flashButton.alpha = if (flashMode == ImageCapture.FLASH_MODE_OFF) 0.6f else 1f
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

    // ======================= camera =======================

    private fun bindCameraUseCases() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            cameraProvider?.unbindAll()
            rebindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera(withAnalysis: Boolean = false): Boolean {
        val provider = cameraProvider ?: return false
        val surface = glSurface ?: return false
        provider.unbindAll()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        // ImageCapture always needs a high-quality JPEG.
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()

        val preview = Preview.Builder().build().also { p ->
            p.setSurfaceProvider { request ->
                binding.glSurfaceView.setBufferSize(request.resolution.width, request.resolution.height)
                binding.glSurfaceView.renderer.frontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT
                request.provideSurface(surface, cameraExecutor) {}
            }
        }

        val useCases = arrayListOf<UseCase>(preview, imageCapture!!)
        if (withAnalysis) {
            if (imageAnalysis == null) {
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1280, 720))
                    .build()
            }
            imageAnalysis?.setAnalyzer(cameraExecutor, VideoAnalyzer())
            useCases.add(requireNotNull(imageAnalysis))
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        return try {
            camera = provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            sensorRotationDegrees = camera?.cameraInfo?.sensorRotationDegrees ?: 0
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun switchCamera() {
        if (isRecording) stopRecording()
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        rebindCamera()
    }

    // ======================= tap to focus =======================

    private fun handleTapToFocus(nx: Float, ny: Float) {
        val cam = camera ?: return
        val control = cam.cameraControl ?: return

        // Show the focus ring at the tapped (view) location.
        showFocusRing(nx, ny)

        // Convert the tap to a sensor-normalized metering point, accounting for
        // the aspect-fit crop and the camera's sensor rotation + facing.
        val factory = createMeteringPointFactory()
        val point = factory.createPoint(nx, ny)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        control.startFocusAndMetering(action).addListener({
            // FocusMeteringResult could tint the ring green/amber; keep it simple.
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Maps a tap in the viewfinder (normalized view coords) to a sensor
     * metering point, matching the renderer's aspect-fit crop, the sensor
     * rotation and the front-camera mirror so the focus square lands where the
     * user actually touched.
     */
    private fun createMeteringPointFactory(): MeteringPointFactory {
        val rotation = sensorRotationDegrees
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val crop = binding.glSurfaceView.renderer.computeCrop()
        val cropU = crop[0]
        val cropV = crop[1]

        return object : MeteringPointFactory() {
            // convertPoint maps viewfinder coords -> sensor-normalized coords.
            override fun convertPoint(x: Float, y: Float): PointF {
                // Undo the aspect-fit crop to get coords in the full buffer image.
                val cx = if (cropU < 1f) (1f - cropU) / 2f + cropU * x else x
                val cy = if (cropV < 1f) (1f - cropV) / 2f + cropV * y else y

                // Rotate view coords back into the sensor frame (origin =
                // top-left of the sensor image).
                val (sx, sy) = when (rotation) {
                    90 -> (1 - cy) to cx
                    180 -> (1 - cx) to (1 - cy)
                    270 -> cy to (1 - cx)
                    else -> cx to cy
                }
                return PointF(if (front) 1 - sx else sx, sy)
            }
        }
    }

    private fun showFocusRing(nx: Float, ny: Float) {
        val ring = binding.focusIndicator
        ring.visibility = View.VISIBLE
        val parent = ring.parent as? View ?: return
        // The ring is constraint-anchored dead-centre; offset via translation so
        // the anchor never fights the position.
        val parentW = parent.width
        val parentH = parent.height
        if (parentW > 0 && parentH > 0) {
            ring.translationX = nx * parentW - parentW / 2f
            ring.translationY = ny * parentH - parentH / 2f
        }
        ring.animate().cancel()
        ring.alpha = 1f
        ring.animate().scaleX(0.8f).scaleY(0.8f).setDuration(120).withEndAction {
            ring.animate().scaleX(1f).scaleY(1f).setDuration(80).withEndAction {
                ring.postDelayed({
                    ring.animate().alpha(0f).setDuration(400).withEndAction {
                        ring.visibility = View.GONE
                        ring.alpha = 1f
                    }.start()
                }, 900)
            }.start()
        }.start()
    }

    // ======================= still capture =======================

    private fun onShutterPressed() {
        if (videoMode) {
            toggleRecording()
            return
        }
        val capture = imageCapture ?: return

        // Timer countdown, if set.
        if (timerSeconds > 0) {
            flashScreen()
            mainHandler.postDelayed({ captureStill(capture) }, timerSeconds * 1000L)
            return
        }
        captureStill(capture)
    }

    private fun captureStill(capture: ImageCapture) {
        flashScreen()
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                processingExecutor.execute {
                    handleCapturedBitmap(bitmap)
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
            ?: return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

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

    private fun handleCapturedBitmap(bitmap: Bitmap) {
        val processed = PhotoProcessor.process(
            bitmap,
            beautyIntensity = 0f,
            filterIntensity = if (vibeEnabled) 1f else 0f,
            vibe = vibeEnabled
        )
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

    // ======================= video =======================

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (isRecording) return
        if (cameraProvider == null || glSurface == null) {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecordingInternal()
        } else {
            requestAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecordingInternal() {
        if (isRecording) return
        toggledRecordUi(true)

        val file = File(cacheDir, "yoeshi_rec_${System.currentTimeMillis()}.mp4")
        recordFile = file
        videoRecorder = VideoRecorder(file)
        isRecording = true

        if (!rebindCamera(withAnalysis = true)) {
            // Camera wouldn't accept the extra analysis stream - roll back.
            rollbackRecording(file)
            return
        }

        // Set the mode bar to "Video" while recording but keep it; show REC dot.
        binding.recordingIndicator.visibility = View.VISIBLE
    }

    private fun toggledRecordUi(on: Boolean) {
        // Change shutter inner from white to red rounded square while recording.
        if (on) {
            binding.shutterButton.setBackgroundResource(R.drawable.record_stop_button)
            binding.shutterButton.alpha = 0.9f
        } else {
            binding.shutterButton.setBackgroundResource(R.drawable.shutter_button)
            binding.shutterButton.alpha = 1f
        }
        binding.recordingIndicator.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun rollbackRecording(file: File) {
        isRecording = false
        videoRecorder = null
        recordFile = null
        file.delete()
        toggledRecordUi(false)
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        toggledRecordUi(false)

        imageAnalysis?.clearAnalyzer()
        rebindCamera(withAnalysis = false)

        cameraExecutor.execute {
            try {
                videoRecorder?.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "Video stop failed", t)
            } finally {
                val file = recordFile
                videoRecorder = null
                recordFile = null

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

    /** Feeds every camera frame to the GPU recorder (shader-matched + audio). */
    private inner class VideoAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            try {
                val recorder = videoRecorder ?: run { image.close(); return }
                val bitmap = YuvConverter.toBitmap(image)
                if (bitmap.width < 2 || bitmap.height < 2) {
                    image.close()
                    return
                }

                if (!recorder.isRecording) {
                    recorder.start(
                        bitmap.width, bitmap.height,
                        image.imageInfo.rotationDegrees,
                        vibeEnabled
                    )
                }
                recorder.frame(bitmap)
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