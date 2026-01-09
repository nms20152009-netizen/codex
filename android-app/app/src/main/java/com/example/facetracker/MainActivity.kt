package com.example.facetracker

import android.Manifest
import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: FaceOverlayView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var voiceCommandManager: VoiceCommandManager? = null
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var lastAutoFrameMs = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startCamera()
            startVoiceCommands()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        statusText.setText(R.string.status_ready)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
            startVoiceCommands()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCommandManager?.release()
        cameraExecutor.shutdown()
    }

    private fun allPermissionsGranted(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return cameraGranted && audioGranted
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor, FaceAnalyzer())

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this,
                selector,
                preview,
                imageCapture,
                videoCapture,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startVoiceCommands() {
        voiceCommandManager = VoiceCommandManager(
            this,
            onCommand = { command ->
                when (command) {
                    VoiceCommand.StartRecording -> startRecording()
                    VoiceCommand.StopRecording -> stopRecording()
                    VoiceCommand.CapturePhoto -> takePhoto()
                }
            },
            onListeningState = { isListening ->
                if (isListening) {
                    statusText.setText(R.string.status_listening)
                }
            }
        ).also { it.start() }
    }

    private fun startRecording() {
        val videoCapture = videoCapture ?: return
        if (currentRecording != null) return

        val name = "VID_${dateFormat.format(System.currentTimeMillis())}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Start) {
                    statusText.setText(R.string.status_recording)
                } else if (event is VideoRecordEvent.Finalize) {
                    statusText.setText(R.string.status_ready)
                    currentRecording = null
                }
            }
        currentRecording = recording
    }

    private fun stopRecording() {
        currentRecording?.stop()
        currentRecording = null
        statusText.setText(R.string.status_ready)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val name = "IMG_${dateFormat.format(System.currentTimeMillis())}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    statusText.setText(R.string.status_ready)
                }

                override fun onError(exception: ImageCaptureException) {
                    statusText.setText(R.string.status_ready)
                }
            }
        )
    }

    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        private val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .enableTracking()
                .build()
        )

        override fun analyze(image: ImageProxy) {
            val mediaImage = image.image
            if (mediaImage == null) {
                image.close()
                return
            }
            val rotationDegrees = image.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        overlayView.clearFaces()
                        return@addOnSuccessListener
                    }
                    val rects = faces.map { face ->
                        RectF(face.boundingBox)
                    }
                    overlayView.updateFaces(rects, image.width, image.height, rotationDegrees)
                    val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (primaryFace != null) {
                        updateAutoFrame(primaryFace.boundingBox, image.width, image.height)
                    }
                }
                .addOnFailureListener {
                    overlayView.clearFaces()
                }
                .addOnCompleteListener {
                    image.close()
                }
        }
    }

    private fun updateAutoFrame(faceBounds: android.graphics.Rect, imageWidth: Int, imageHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAutoFrameMs < 500) return
        lastAutoFrameMs = now

        val camera = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(
            imageWidth.toFloat(),
            imageHeight.toFloat()
        )
        val point = factory.createPoint(
            faceBounds.centerX().toFloat(),
            faceBounds.centerY().toFloat()
        )
        camera.cameraControl.startFocusAndMetering(
            androidx.camera.core.FocusMeteringAction.Builder(point).build()
        )

        val targetFaceRatio = 0.45f
        val currentRatio = max(
            faceBounds.width().toFloat() / imageWidth,
            faceBounds.height().toFloat() / imageHeight
        )
        val desiredZoom = if (currentRatio > 0f) {
            min(1f, max(0f, targetFaceRatio / currentRatio))
        } else {
            0f
        }
        camera.cameraControl.setLinearZoom(desiredZoom)
    }
}
