package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ui.components.analyzeFaceBiometrics
import com.example.ui.components.captureRotatedSelfieFile
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalGetImage::class)
@Composable
internal fun LiveRegistrationCameraOverlay(
    employeeIndex: Int,
    onCaptured: (photoPath: String, faceSignature: String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var faceLocked by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Align Employee #$employeeIndex inside the frame") }
    var capturedReadyPath by remember { mutableStateOf<String?>(null) }
    var capturedReadySig by remember { mutableStateOf<String?>(null) }
    val isFrontRef = remember { AtomicBoolean(true) }
    val latestValidSig = remember { AtomicReference<String?>(null) }
    val latestPhotoPath = remember { AtomicReference<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        if (hasPermission) {
            val previewView = remember(context) {
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    keepScreenOn = true
                }
            }
            val executor = remember { Executors.newSingleThreadExecutor() }
            val detector = remember {
                FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setMinFaceSize(0.18f)
                        .build()
                )
            }

            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            DisposableEffect(lifecycleOwner, hasPermission) {
                var disposed = false
                var provider: ProcessCameraProvider? = null
                val providerFuture = ProcessCameraProvider.getInstance(context)

                providerFuture.addListener({
                    if (disposed) return@addListener
                    try {
                        val cameraProvider = providerFuture.get()
                        provider = cameraProvider

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(executor) { imageProxy ->
                                    if (disposed) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val frontCam = isFrontRef.get()
                                        val rot = imageProxy.imageInfo.rotationDegrees
                                        val snapPath = captureRotatedSelfieFile(
                                            context = appContext,
                                            imageProxy = imageProxy,
                                            isFrontCamera = frontCam,
                                            rotationDegrees = rot,
                                            targetDirName = "registration_temp",
                                            filePrefix = "reg_emp_$employeeIndex",
                                            maxKeepCount = 3
                                        )
                                        val input = InputImage.fromMediaImage(mediaImage, rot)
                                        detector.process(input)
                                            .addOnSuccessListener { faces ->
                                                if (!disposed) {
                                                    val face = faces.maxByOrNull {
                                                        it.boundingBox.width() * it.boundingBox.height()
                                                    }
                                                    if (face != null) {
                                                        val analyzed = analyzeFaceBiometrics(
                                                            face = face,
                                                            isFrontCamera = frontCam,
                                                            capturedSelfiePath = snapPath
                                                        )
                                                        if (analyzed.signature != null && analyzed.headPoseAligned && snapPath != null) {
                                                            latestValidSig.set(analyzed.signature)
                                                            latestPhotoPath.set(snapPath)
                                                            capturedReadySig = analyzed.signature
                                                            capturedReadyPath = snapPath
                                                            faceLocked = true
                                                            statusText = "Face Locked • Tap Capture Photo"
                                                        } else {
                                                            faceLocked = false
                                                            statusText = analyzed.qualityIssue ?: "Look straight into camera"
                                                        }
                                                    } else {
                                                        faceLocked = false
                                                        statusText = "No face detected • Center face inside box"
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }

                        val selector = when {
                            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                                isFrontRef.set(true)
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            }
                            cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                                isFrontRef.set(false)
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }
                            else -> null
                        }
                        if (selector != null) {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
                        } else {
                            statusText = "No camera sensor available"
                        }
                    } catch (e: Exception) {
                        Log.w("RegistrationCamera", "Camera init error: ${e.message}")
                        statusText = e.localizedMessage ?: "Unable to start camera"
                    }
                }, ContextCompat.getMainExecutor(context))

                onDispose {
                    disposed = true
                    runCatching { provider?.unbindAll() }
                    runCatching { detector.close() }
                    runCatching { executor.shutdown() }
                }
            }

            // Crisp face-alignment reticle
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxW = minOf(size.width * 0.72f, size.height * 0.44f)
                val boxH = boxW * 1.24f
                val left = (size.width - boxW) / 2f
                val top = (size.height - boxH) * 0.45f
                drawOval(
                    color = if (faceLocked) NeonGreen else NeonCyan,
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = ObsidianVoid),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(stringResource(R.string.grant_camera_permission), fontWeight = FontWeight.Bold)
                }
            }
        }

        // Top close button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(16.dp)
                .clip(CircleShape)
                .background(ObsidianSurface.copy(alpha = 0.85f))
                .minimumInteractiveComponentSize()
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel capture",
                tint = TextPrimary
            )
        }

        // Bottom capture controls
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = ObsidianSurface.copy(alpha = 0.94f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (faceLocked) NeonGreen else TextSecondary,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        val path = capturedReadyPath ?: latestPhotoPath.get()
                        val sig = capturedReadySig ?: latestValidSig.get()
                        if (!path.isNullOrBlank() && !sig.isNullOrBlank()) {
                            onCaptured(path, sig)
                        }
                    },
                    enabled = !capturedReadyPath.isNullOrBlank() && !capturedReadySig.isNullOrBlank(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonGreen,
                        contentColor = ObsidianVoid,
                        disabledContainerColor = ObsidianElevated,
                        disabledContentColor = TextMuted
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("confirm_capture_photo_button")
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (faceLocked) "Capture & Save Face" else "Waiting for Face Lock...",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
