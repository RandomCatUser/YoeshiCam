package com.example.yucam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.yucam.camera.FaceBeautyAnalyzer
import com.example.yucam.gl.CameraGLRenderer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentRenderer: CameraGLRenderer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: true
            if (!cameraGranted) {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            var hasPermission by remember { mutableStateOf(allPermissionsGranted()) }
            var recordingState by remember { mutableStateOf(false) }

            if (hasPermission) {
                CameraScreen(
                    onCaptureClick = { takePhoto() },
                    onToggleRecording = { startStopRecording(onRecordingStateChange = { recordingState = it }) },
                    onFlipCamera = { flipCamera() },
                    isRecording = recordingState,
                    onRecordingStateChange = { recordingState = it }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        requestPermissionLauncher.launch(arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        ))
                    }) {
                        Text("Grant Permissions")
                    }
                }
            }
        }

        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ))
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return cameraGranted
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = "YuCam_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Yu-Cam")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Photo capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Photo saved!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun startStopRecording(onRecordingStateChange: (Boolean) -> Unit) {
        val recording = recording
        if (recording != null) {
            // Stop recording
            recording.stop()
            this.recording = null
            return
        }

        val videoCapture = videoCapture ?: return

        val name = "YuCam_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Yu-Cam")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        val currentRecording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .let { pendingRecording ->
                val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (audioGranted) pendingRecording.withAudioEnabled() else pendingRecording
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        onRecordingStateChange(true)
                        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        onRecordingStateChange(false)
                        val message = if (event.hasError()) {
                            "Recording failed: ${event.cause?.message ?: "unknown error"}"
                        } else {
                            "Video saved!"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }

        this.recording = currentRecording
    }

    private fun flipCamera() {
        if (recording != null) return

        lensFacing = if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val provider = cameraProvider ?: return
        val renderer = currentRenderer ?: return
        val surfaceTexture = renderer.getSurfaceTexture() ?: return
        bindCamera(provider, renderer, surfaceTexture)
    }

    private fun bindCamera(provider: ProcessCameraProvider, renderer: CameraGLRenderer, surfaceTexture: android.graphics.SurfaceTexture) {
        // Release previous binding
        provider.unbindAll()

        val preview = Preview.Builder()
            .setResolutionStrategy(androidx.camera.resolutionselector.ResolutionStrategy(
                android.util.Size(1920, 1080),
                androidx.camera.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            ))
            .build()

        // Provide the GL surface texture as the preview output surface
        preview.setSurfaceProvider { request ->
            val surface = android.view.Surface(surfaceTexture)
            request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {
                surface.release()
            }
        }

        // ImageCapture Use Case
        val imageCapture = ImageCapture.Builder().build()
        this.imageCapture = imageCapture

        // VideoCapture with Recorder
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.HD))
            )
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        this.videoCapture = videoCapture

        // ImageAnalysis Use Case for ML Kit Beauty
        val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer(
            ContextCompat.getMainExecutor(this),
            FaceBeautyAnalyzer { facesDetected ->
                // Pass uniform to shader or update UI State here
            }
        )

        try {
            provider.bindToLifecycle(
                lifecycle, lensFacing, preview, imageCapture, videoCapture, imageAnalysis
            )
        } catch (exc: Exception) {
            Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        recording?.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}

@Composable
fun CameraScreen(
    onCaptureClick: () -> Unit,
    onToggleRecording: () -> Unit,
    onFlipCamera: () -> Unit,
    isRecording: Boolean,
    onRecordingStateChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    val renderer = CameraGLRenderer()
                    renderer.setGlSurfaceView(this)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

                    renderer.onSurfaceTextureAvailable = { surfaceTexture ->
                        // Initialize CameraX Preview
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                            (context as MainActivity).cameraProvider = cameraProvider
                            (context as MainActivity).currentRenderer = renderer
                            (context as MainActivity).bindCamera(cameraProvider, renderer, surfaceTexture)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Minimal UI Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("4:3", color = Color.White)
                Text("100%", color = Color.White) // Beauty toggle placeholder
            }

            // Bottom Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp).background(Color.DarkGray)) // Gallery Thumb

                // Record button
                Button(
                    onClick = { onToggleRecording() },
                    modifier = Modifier.size(72.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFB00020) else Color.DarkGray
                    )
                ) {
                    Text(if (isRecording) "Stop" else "Rec", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }

                // Capture button
                Button(
                    onClick = onCaptureClick,
                    modifier = Modifier.size(64.dp)
                ) {
                    Text("Snap")
                }

                Text(
                    "Flip",
                    color = if (isRecording) Color.Gray else Color.White,
                    modifier = Modifier.clickable { if (!isRecording) onFlipCamera() }
                )
            }
        }
    }
}