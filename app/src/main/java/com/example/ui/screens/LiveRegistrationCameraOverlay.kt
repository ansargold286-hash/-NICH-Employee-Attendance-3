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
import androidx.compose.material.icons.rounded.Badge
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.example.R
import com.example.ui.components.analyzeFaceBiometrics
import com.example.ui.components.captureRotatedCnicCardFile
import com.example.ui.components.captureRotatedSelfieFile
import com.example.ui.components.drawGreenCnicGuideFrame
import com.example.ui.components.evaluateVisionTextInGreenFrame
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class RegistrationCaptureStep {
    FACE_FRONT,
    CNIC_REAR
}

@OptIn(ExperimentalGetImage::class)
@Composable
internal fun LiveRegistrationCameraOverlay(
    employeeIndex: Int,
    onCaptured: (
        facePhotoPath: String,
        faceSignature: String,
        cnicPhotoPath: String,
        cnicNumber: String,
        cnicName: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler { onDismiss() }

    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCaptured by rememberUpdatedState(onCaptured)

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

    var captureStep by remember { mutableStateOf(RegistrationCaptureStep.FACE_FRONT) }
    var faceLocked by remember { mutableStateOf(false) }
    var cnicAligned by remember { mutableStateOf(false) }
    var cnicStableCount by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Step 1/2: Align Employee #$employeeIndex inside the frame") }
    var capturedReadyPath by remember { mutableStateOf<String?>(null) }
    var capturedReadySig by remember { mutableStateOf<String?>(null) }
    var capturedFaceConfirmedPath by remember { mutableStateOf<String?>(null) }
    var capturedFaceConfirmedSig by remember { mutableStateOf<String?>(null) }
    var latestRearCnicSnapshotPath by remember { mutableStateOf<String?>(null) }
    var detectedCnicNumber by remember { mutableStateOf("") }
    var detectedCnicName by remember { mutableStateOf("") }

    val isFrontRef = remember { AtomicBoolean(true) }
    val latestValidSig = remember { AtomicReference<String?>(null) }
    val latestPhotoPath = remember { AtomicReference<String?>(null) }
    val autoSnapCompletedRef = remember { AtomicBoolean(false) }

    val sweepTransition = rememberInfiniteTransition(label = "regScanSweep")
    val sweepFraction by sweepTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cnicRegSweep"
    )

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
            val textRecognizer = remember {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }

            DisposableEffect(Unit) {
                onDispose {
                    runCatching { detector.close() }
                    runCatching { textRecognizer.close() }
                    runCatching { executor.shutdown() }
                }
            }

            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            DisposableEffect(lifecycleOwner, hasPermission, captureStep) {
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

                                        if (captureStep == RegistrationCaptureStep.FACE_FRONT) {
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
                                                                statusText = "Face Locked • Tap Capture Face & Next CNIC"
                                                            } else {
                                                                faceLocked = false
                                                                statusText = analyzed.qualityIssue ?: "Look straight into camera"
                                                            }
                                                        } else {
                                                            faceLocked = false
                                                            statusText = "Step 1/2: Center live face inside oval"
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            // Rear Camera Automated CNIC Front-Side Scan with Green Guide Frame
                                            val cnicSnapPath = captureRotatedCnicCardFile(
                                                context = appContext,
                                                imageProxy = imageProxy,
                                                isFrontCamera = frontCam,
                                                rotationDegrees = rot,
                                                targetDirName = "registration_cnic_temp",
                                                filePrefix = "reg_cnic_$employeeIndex",
                                                maxKeepCount = 3
                                            )
                                            if (cnicSnapPath != null) {
                                                latestRearCnicSnapshotPath = cnicSnapPath
                                            }
                                            val input = InputImage.fromMediaImage(mediaImage, rot)
                                            textRecognizer.process(input)
                                                .addOnSuccessListener { visionText ->
                                                    if (disposed || autoSnapCompletedRef.get()) return@addOnSuccessListener
                                                    val (insideGreen, parsed) = evaluateVisionTextInGreenFrame(
                                                        visionText = visionText,
                                                        imageWidth = input.width,
                                                        imageHeight = input.height,
                                                        rotationDegrees = rot
                                                    )
                                                    if (parsed.cnicNumber.isNotBlank()) {
                                                        detectedCnicNumber = parsed.cnicNumber
                                                    }
                                                    if (parsed.extractedName.isNotBlank()) {
                                                        detectedCnicName = parsed.extractedName
                                                    }

                                                    val validCardFrame = insideGreen && parsed.isCnicCardDetected
                                                    cnicAligned = validCardFrame
                                                    if (validCardFrame) {
                                                        cnicStableCount++
                                                        statusText = "CNIC Aligned Inside Green Frame • Auto-Snapping..."
                                                        val bestPath = cnicSnapPath ?: latestRearCnicSnapshotPath
                                                        if (cnicStableCount >= 2 && bestPath != null &&
                                                            autoSnapCompletedRef.compareAndSet(false, true)
                                                        ) {
                                                            val facePath = capturedFaceConfirmedPath ?: capturedReadyPath.orEmpty()
                                                            val faceSig = capturedFaceConfirmedSig ?: capturedReadySig.orEmpty()
                                                            latestOnCaptured(
                                                                facePath,
                                                                faceSig,
                                                                bestPath,
                                                                parsed.cnicNumber.ifBlank { detectedCnicNumber },
                                                                parsed.extractedName.ifBlank { detectedCnicName }
                                                            )
                                                        }
                                                    } else {
                                                        cnicStableCount = 0
                                                        statusText = "Step 2/2: Hold CNIC Front Side inside the Green Guide Frame"
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }

                        val selector = if (captureStep == RegistrationCaptureStep.FACE_FRONT) {
                            when {
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
                        } else {
                            when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                                    isFrontRef.set(false)
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                }
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                                    isFrontRef.set(true)
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                }
                                else -> null
                            }
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
                }
            }

            if (captureStep == RegistrationCaptureStep.FACE_FRONT) {
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
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawGreenCnicGuideFrame(
                        isAligned = cnicAligned,
                        sweepFraction = sweepFraction
                    )
                }
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (faceLocked || cnicAligned) NeonGreen else TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (captureStep == RegistrationCaptureStep.CNIC_REAR &&
                    (detectedCnicName.isNotBlank() || detectedCnicNumber.isNotBlank())
                ) {
                    Text(
                        text = listOfNotNull(
                            detectedCnicName.takeIf { it.isNotBlank() },
                            detectedCnicNumber.takeIf { it.isNotBlank() }
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        textAlign = TextAlign.Center
                    )
                }

                if (captureStep == RegistrationCaptureStep.FACE_FRONT) {
                    Button(
                        onClick = {
                            val path = capturedReadyPath ?: latestPhotoPath.get()
                            val sig = capturedReadySig ?: latestValidSig.get()
                            if (!path.isNullOrBlank() && !sig.isNullOrBlank()) {
                                capturedFaceConfirmedPath = path
                                capturedFaceConfirmedSig = sig
                                cnicStableCount = 0
                                cnicAligned = false
                                autoSnapCompletedRef.set(false)
                                statusText = "Step 2/2: Hold CNIC Front Side inside the Green Guide Frame"
                                captureStep = RegistrationCaptureStep.CNIC_REAR
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
                            text = if (faceLocked) "Capture Face & Scan CNIC" else "Waiting for Face Lock...",
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            val snap = latestRearCnicSnapshotPath
                            val facePath = capturedFaceConfirmedPath ?: capturedReadyPath
                            val faceSig = capturedFaceConfirmedSig ?: capturedReadySig
                            if (!snap.isNullOrBlank() && !facePath.isNullOrBlank() && !faceSig.isNullOrBlank() &&
                                autoSnapCompletedRef.compareAndSet(false, true)
                            ) {
                                latestOnCaptured(
                                    facePath,
                                    faceSig,
                                    snap,
                                    detectedCnicNumber,
                                    detectedCnicName
                                )
                            }
                        },
                        enabled = !latestRearCnicSnapshotPath.isNullOrBlank(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cnicAligned) NeonGreen else NeonCyan,
                            contentColor = ObsidianVoid,
                            disabledContainerColor = ObsidianElevated,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("confirm_capture_cnic_button")
                    ) {
                        Icon(Icons.Rounded.Badge, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (cnicAligned) "Auto-Snapping Aligned CNIC..." else "Snap CNIC Front Inside Green Frame",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
