package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.FaceBiometricMatcher
import com.example.data.model.LivenessDirection
import com.example.data.model.LivenessEvaluator
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(ExperimentalGetImage::class)
@Composable
fun BiometricCameraScanner(
    attemptNumber: Int,
    cameraReady: Boolean,
    faceDetected: Boolean,
    eyesLocked: Boolean,
    noseMustacheLocked: Boolean,
    jawBeardLocked: Boolean,
    livenessSteps: List<LivenessDirection>,
    currentLivenessStepIndex: Int,
    qualityHint: String?,
    onCameraInitialized: () -> Unit,
    onCameraInitFailed: (String) -> Unit,
    onFrameAnalyzed: (FaceAnalysisFrame) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    val activeDirection = livenessSteps.getOrElse(currentLivenessStepIndex) {
        LivenessDirection.CENTER_VERIFY
    }

    val latestCameraInit by rememberUpdatedState(onCameraInitialized)
    val latestCameraFail by rememberUpdatedState(onCameraInitFailed)
    val latestFrameAnalyzed by rememberUpdatedState(onFrameAnalyzed)

    val activeDirectionRef = remember { AtomicReference(activeDirection) }
    activeDirectionRef.set(activeDirection)
    val isFrontCameraRef = remember { AtomicBoolean(true) }
    val lastSelfieWriteMs = remember { AtomicLong(0L) }
    val lastSelfiePathRef = remember { AtomicReference<String?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val allFeaturesLocked = eyesLocked && noseMustacheLocked && jawBeardLocked
    val hudColor = when {
        allFeaturesLocked && activeDirection == LivenessDirection.CENTER_VERIFY -> NeonGreen
        faceDetected -> NeonCyan
        attemptNumber >= 2 -> NeonAmber
        else -> NeonCyan
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scannerHudAnim")
    val sweepFraction by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sweepFraction"
    )
    val arrowPulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrowPulse"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Multi-step Liveness Progress Pills
        LivenessStepProgressBar(
            steps = livenessSteps,
            currentStepIndex = currentLivenessStepIndex
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(336.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ObsidianVoid)
                .border(2.dp, hudColor, RoundedCornerShape(20.dp))
        ) {
            if (hasCameraPermission) {
                val previewView = remember(context) {
                    PreviewView(context).apply {
                        // COMPATIBLE mode (TextureView) keeps camera feed live & stable without turning black
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        keepScreenOn = true
                    }
                }
                val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                val faceDetector = remember {
                    val options = FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setMinFaceSize(0.18f)
                        .build()
                    FaceDetection.getClient(options)
                }

                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Keyed ONLY on (lifecycleOwner, hasCameraPermission) so liveness step changes never glitch/reset the camera
                DisposableEffect(lifecycleOwner, hasCameraPermission) {
                    var isDisposed = false
                    var boundProvider: ProcessCameraProvider? = null
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                    cameraProviderFuture.addListener({
                        if (isDisposed) return@addListener
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            boundProvider = cameraProvider

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (isDisposed) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val stepSnapshot = activeDirectionRef.get()
                                            val frontCam = isFrontCameraRef.get()
                                            val rotationDeg = imageProxy.imageInfo.rotationDegrees

                                            if (stepSnapshot == LivenessDirection.CENTER_VERIFY) {
                                                val nowMs = System.currentTimeMillis()
                                                if (nowMs - lastSelfieWriteMs.get() >= 450L) {
                                                    val capturedPath = captureRotatedSelfieFile(
                                                        context = appContext,
                                                        imageProxy = imageProxy,
                                                        isFrontCamera = frontCam,
                                                        rotationDegrees = rotationDeg
                                                    )
                                                    if (capturedPath != null) {
                                                        lastSelfiePathRef.set(capturedPath)
                                                        lastSelfieWriteMs.set(nowMs)
                                                    }
                                                }
                                            }

                                            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDeg)
                                            val selfieForFrame = if (stepSnapshot == LivenessDirection.CENTER_VERIFY) {
                                                lastSelfiePathRef.get()
                                            } else {
                                                null
                                            }

                                            faceDetector.process(inputImage)
                                                .addOnSuccessListener { faces ->
                                                    if (!isDisposed) {
                                                        val primaryFace = faces.maxByOrNull {
                                                            it.boundingBox.width() * it.boundingBox.height()
                                                        }
                                                        if (primaryFace != null) {
                                                            latestFrameAnalyzed(
                                                                analyzeFaceBiometrics(
                                                                    face = primaryFace,
                                                                    targetDirection = activeDirectionRef.get(),
                                                                    isFrontCamera = frontCam,
                                                                    capturedSelfiePath = selfieForFrame
                                                                )
                                                            )
                                                        } else {
                                                            latestFrameAnalyzed(
                                                                FaceAnalysisFrame(
                                                                    faceDetected = false,
                                                                    isFrontCamera = frontCam
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    if (!isDisposed) {
                                                        latestFrameAnalyzed(
                                                            FaceAnalysisFrame(
                                                                faceDetected = false,
                                                                isFrontCamera = frontCam
                                                            )
                                                        )
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

                            val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                            val hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                            val selector = when {
                                hasFront -> {
                                    isFrontCameraRef.set(true)
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                }
                                hasBack -> {
                                    isFrontCameraRef.set(false)
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                }
                                else -> null
                            }

                            if (selector == null) {
                                latestCameraFail("No physical front or back camera sensor detected on this device.")
                                return@addListener
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                preview,
                                imageAnalyzer
                            )
                            if (!isDisposed) {
                                latestCameraInit()
                            }
                        } catch (e: Exception) {
                            Log.w("BiometricCamera", "CameraX initialization error: ${e.message}", e)
                            if (!isDisposed) {
                                latestCameraFail(
                                    e.localizedMessage ?: "CameraX hardware initialization failed."
                                )
                            }
                        }
                    }, ContextCompat.getMainExecutor(context))

                    onDispose {
                        isDisposed = true
                        runCatching { boundProvider?.unbindAll() }
                        runCatching { faceDetector.close() }
                        runCatching { cameraExecutor.shutdown() }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianElevated)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Camera Required for Facial Liveness & Biometrics",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Strict terminal mode requires live camera access to verify directional liveness, capture a selfie, and log attendance.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = ObsidianVoid
                        ),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Grant Camera Permission", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Neon Biometric Reticle & Laser Sweep HUD
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val reticleSize = minOf(w, h) * 0.60f
                val left = (w - reticleSize) / 2f
                val top = (h - reticleSize) / 2f
                val cornerLen = 28.dp.toPx()
                val strokeW = 3.dp.toPx()

                val corners = listOf(
                    Offset(left, top) to Offset(left + cornerLen, top),
                    Offset(left, top) to Offset(left, top + cornerLen),
                    Offset(left + reticleSize, top) to Offset(left + reticleSize - cornerLen, top),
                    Offset(left + reticleSize, top) to Offset(left + reticleSize, top + cornerLen),
                    Offset(left, top + reticleSize) to Offset(left + cornerLen, top + reticleSize),
                    Offset(left, top + reticleSize) to Offset(left, top + reticleSize - cornerLen),
                    Offset(left + reticleSize, top + reticleSize) to Offset(left + reticleSize - cornerLen, top + reticleSize),
                    Offset(left + reticleSize, top + reticleSize) to Offset(left + reticleSize, top + reticleSize - cornerLen)
                )
                corners.forEach { (start, end) ->
                    drawLine(
                        color = hudColor,
                        start = start,
                        end = end,
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }

                drawOval(
                    color = hudColor.copy(alpha = 0.42f),
                    topLeft = Offset(left + reticleSize * 0.14f, top + reticleSize * 0.06f),
                    size = Size(reticleSize * 0.72f, reticleSize * 0.88f),
                    style = Stroke(width = 1.5.dp.toPx())
                )

                val beamY = h * sweepFraction
                drawLine(
                    color = hudColor.copy(alpha = 0.85f),
                    start = Offset(left, beamY),
                    end = Offset(left + reticleSize, beamY),
                    strokeWidth = 2.dp.toPx()
                )
            }

            // Top telemetry + Directional Liveness Arrow Cue inside scanner viewport
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.86f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(1.dp, hudColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = if (!cameraReady) {
                                "INIT CAMERA"
                            } else {
                                "LIVENESS ${currentLivenessStepIndex + 1}/${livenessSteps.size.coerceAtLeast(1)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = hudColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.86f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(
                            1.dp,
                            if (allFeaturesLocked) NeonGreen else Color.White.copy(alpha = 0.28f),
                            RoundedCornerShape(8.dp)
                        )
                    ) {
                        Text(
                            text = when {
                                allFeaturesLocked -> "FULL MESH LOCKED"
                                faceDetected -> "TRACKING HEAD POSE"
                                else -> "ALIGN FACE"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (allFeaturesLocked) NeonGreen else TextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Active Directional Arrow Prompt Banner
                LivenessDirectionBanner(
                    direction = activeDirection,
                    arrowScale = arrowPulse
                )
            }

            // Bottom live facial feature subsystem readout
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!qualityHint.isNullOrBlank()) {
                    Surface(
                        color = ObsidianVoid.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.border(1.dp, NeonAmber.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = qualityHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonAmber,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    FeatureLockChip(label = "EYES", locked = eyesLocked)
                    FeatureLockChip(label = "MUSTACHE/LIP", locked = noseMustacheLocked)
                    FeatureLockChip(label = "BEARD/JAW", locked = jawBeardLocked)
                }
            }
        }
    }
}

@Composable
private fun LivenessStepProgressBar(
    steps: List<LivenessDirection>,
    currentStepIndex: Int
) {
    if (steps.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        steps.forEachIndexed { idx, step ->
            val completed = idx < currentStepIndex
            val active = idx == currentStepIndex
            val accent = when {
                completed -> NeonGreen
                active -> NeonCyan
                else -> ObsidianOutline
            }
            Surface(
                color = when {
                    completed -> NeonGreen.copy(alpha = 0.14f)
                    active -> NeonCyan.copy(alpha = 0.14f)
                    else -> ObsidianElevated
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, accent, RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (completed) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = "${idx + 1}. ${step.shortLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            completed -> NeonGreen
                            active -> NeonCyan
                            else -> TextSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LivenessDirectionBanner(
    direction: LivenessDirection,
    arrowScale: Float
) {
    val accent = if (direction == LivenessDirection.CENTER_VERIFY) NeonGreen else NeonCyan
    val iconVector = when (direction) {
        LivenessDirection.TURN_LEFT -> Icons.AutoMirrored.Rounded.ArrowBack
        LivenessDirection.TURN_RIGHT -> Icons.AutoMirrored.Rounded.ArrowForward
        LivenessDirection.LOOK_UP -> Icons.Rounded.ArrowUpward
        LivenessDirection.LOOK_DOWN -> Icons.Rounded.ArrowDownward
        LivenessDirection.CENTER_VERIFY -> Icons.Rounded.CenterFocusStrong
    }

    Surface(
        color = ObsidianVoid.copy(alpha = 0.90f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.22f),
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = arrowScale
                        scaleY = arrowScale
                    }
                    .border(1.dp, accent, CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = direction.instruction,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = direction.instruction,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FeatureLockChip(label: String, locked: Boolean) {
    val color = if (locked) NeonGreen else NeonRed.copy(alpha = 0.75f)
    Surface(
        color = ObsidianVoid.copy(alpha = 0.84f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
    ) {
        Text(
            text = if (locked) "✓ $label" else "• $label",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun captureRotatedSelfieFile(
    context: Context,
    imageProxy: ImageProxy,
    isFrontCamera: Boolean,
    rotationDegrees: Int
): String? {
    var rawBitmap: Bitmap? = null
    var transformedBitmap: Bitmap? = null
    var scaledBitmap: Bitmap? = null
    return try {
        rawBitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (isFrontCamera) {
                postScale(-1f, 1f)
            }
        }
        transformedBitmap = Bitmap.createBitmap(
            rawBitmap,
            0,
            0,
            rawBitmap.width,
            rawBitmap.height,
            matrix,
            true
        )

        val maxDim = 480
        val finalBitmap = if (transformedBitmap.width > maxDim) {
            val aspectHeight = ((transformedBitmap.height.toFloat() / transformedBitmap.width.toFloat()) * maxDim)
                .toInt()
                .coerceAtLeast(1)
            scaledBitmap = Bitmap.createScaledBitmap(transformedBitmap, maxDim, aspectHeight, true)
            scaledBitmap
        } else {
            transformedBitmap
        }

        val dir = File(context.cacheDir, "live_selfies").apply {
            if (!exists()) mkdirs()
        }
        // Keep only the most recent few captures so cache stays compact
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { old ->
            runCatching { old.delete() }
        }
        val outFile = File(dir, "selfie_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { stream ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        }
        outFile.absolutePath
    } catch (e: Throwable) {
        Log.w("BiometricCamera", "Live selfie snapshot skipped: ${e.message}")
        null
    } finally {
        if (scaledBitmap != null && scaledBitmap !== transformedBitmap) {
            runCatching { scaledBitmap.recycle() }
        }
        if (transformedBitmap != null && transformedBitmap !== rawBitmap) {
            runCatching { transformedBitmap.recycle() }
        }
        if (rawBitmap != null) {
            runCatching { rawBitmap.recycle() }
        }
    }
}

/**
 * Extracts multi-dimensional facial biometrics covering eyes, nose bridge,
 * philtrum/mustache region, mouth width, jawline/beard drop, cheek structure,
 * and evaluates live directional head-pose liveness angles.
 */
internal fun analyzeFaceBiometrics(
    face: Face,
    targetDirection: LivenessDirection = LivenessDirection.CENTER_VERIFY,
    isFrontCamera: Boolean = true,
    capturedSelfiePath: String? = null
): FaceAnalysisFrame {
    val box: Rect = face.boundingBox
    val boxW = box.width().toFloat().coerceAtLeast(1f)
    val boxH = box.height().toFloat().coerceAtLeast(1f)

    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
    val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
    val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
    val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

    val faceContour = face.getContour(FaceContour.FACE)?.points.orEmpty()
    val upperLipContour = face.getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty()

    val eyesLocked = leftEye != null && rightEye != null
    val noseMustacheLocked = nose != null && mouthLeft != null && mouthRight != null
    val jawBeardLocked = faceContour.size >= 19 || (mouthBottom != null && (leftCheek != null || rightCheek != null))

    val yaw = face.headEulerAngleY
    val pitch = face.headEulerAngleX
    val roll = face.headEulerAngleZ

    val leftOpenProb = face.leftEyeOpenProbability
    val rightOpenProb = face.rightEyeOpenProbability
    val livenessOpenEyes = (leftOpenProb == null || leftOpenProb >= 0.25f) &&
        (rightOpenProb == null || rightOpenProb >= 0.25f)

    // For directional liveness challenges, track head pose cleanly without blocking turned poses
    if (targetDirection != LivenessDirection.CENTER_VERIFY) {
        val directionMatched = LivenessEvaluator.isDirectionSatisfied(
            direction = targetDirection,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            isFrontCamera = isFrontCamera
        )
        return FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = eyesLocked,
            noseMustacheLocked = noseMustacheLocked,
            jawBeardLocked = jawBeardLocked,
            signature = null,
            livenessOpenEyes = livenessOpenEyes,
            headPoseAligned = directionMatched,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            headRollDeg = roll,
            isFrontCamera = isFrontCamera,
            selfieImagePath = null,
            qualityIssue = if (directionMatched) null else targetDirection.instruction
        )
    }

    val headPoseAligned = abs(yaw) <= 20f && abs(pitch) <= 20f && abs(roll) <= 18f

    if (!eyesLocked || !noseMustacheLocked) {
        return FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = eyesLocked,
            noseMustacheLocked = false,
            jawBeardLocked = jawBeardLocked,
            signature = null,
            livenessOpenEyes = livenessOpenEyes,
            headPoseAligned = headPoseAligned,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            headRollDeg = roll,
            isFrontCamera = isFrontCamera,
            qualityIssue = "Center eyes, nose, and mouth inside reticle"
        )
    }

    if (!headPoseAligned) {
        return FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = true,
            noseMustacheLocked = true,
            jawBeardLocked = jawBeardLocked,
            signature = null,
            livenessOpenEyes = livenessOpenEyes,
            headPoseAligned = false,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            headRollDeg = roll,
            isFrontCamera = isFrontCamera,
            qualityIssue = "Look straight into camera lens for selfie match"
        )
    }

    if (!livenessOpenEyes) {
        return FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = false,
            noseMustacheLocked = true,
            jawBeardLocked = jawBeardLocked,
            signature = null,
            livenessOpenEyes = false,
            headPoseAligned = true,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            headRollDeg = roll,
            isFrontCamera = isFrontCamera,
            qualityIssue = "Keep both eyes open for verification"
        )
    }

    val lEye = leftEye!!
    val rEye = rightEye!!
    val nosePos = nose!!
    val mLeft = mouthLeft!!
    val mRight = mouthRight!!

    val eyeDist = hypot(rEye.x - lEye.x, rEye.y - lEye.y).coerceAtLeast(1f)
    val eyeMidX = (lEye.x + rEye.x) / 2f
    val eyeMidY = (lEye.y + rEye.y) / 2f

    val mouthDist = hypot(mRight.x - mLeft.x, mRight.y - mLeft.y)
    val noseDrop = hypot(nosePos.x - eyeMidX, nosePos.y - eyeMidY)

    val upperLipMid = if (upperLipContour.isNotEmpty()) {
        upperLipContour[upperLipContour.size / 2]
    } else {
        null
    }
    val mouthCenterX = (mLeft.x + mRight.x) / 2f
    val mouthCenterY = (mLeft.y + mRight.y) / 2f
    val philtrumDist = if (upperLipMid != null) {
        hypot(upperLipMid.x - nosePos.x, upperLipMid.y - nosePos.y)
    } else {
        hypot(mouthCenterX - nosePos.x, mouthCenterY - nosePos.y)
    }

    val chinPoint = if (faceContour.size >= 19) {
        faceContour.maxByOrNull { it.y }
    } else {
        null
    }
    val jawChinDrop = when {
        chinPoint != null -> abs(chinPoint.y - mouthCenterY)
        mouthBottom != null -> abs(box.bottom.toFloat() - mouthBottom.y)
        else -> abs(box.bottom.toFloat() - mouthCenterY)
    }

    val jawWidthToCheek = when {
        faceContour.size >= 30 -> {
            val upperCheekSpan = hypot(
                faceContour[6].x - faceContour[30].x,
                faceContour[6].y - faceContour[30].y
            ).coerceAtLeast(1f)
            val lowerJawSpan = hypot(
                faceContour[12].x - faceContour[24].x,
                faceContour[12].y - faceContour[24].y
            )
            (lowerJawSpan / upperCheekSpan).coerceIn(0.35f, 1.15f)
        }
        leftCheek != null && rightCheek != null -> {
            val cheekSpan = hypot(rightCheek.x - leftCheek.x, rightCheek.y - leftCheek.y)
            (cheekSpan / boxW).coerceIn(0.35f, 1.15f)
        }
        else -> 0.72f
    }

    val noseToLeftEye = hypot(nosePos.x - lEye.x, nosePos.y - lEye.y).coerceAtLeast(1f)
    val noseToRightEye = hypot(nosePos.x - rEye.x, nosePos.y - rEye.y).coerceAtLeast(1f)
    val symmetryRatio = (minOf(noseToLeftEye, noseToRightEye) / maxOf(noseToLeftEye, noseToRightEye))
        .coerceIn(0.5f, 1.0f)

    val signature = FaceBiometricMatcher.encodeFullSignature(
        eyeToBoxRatio = (eyeDist / boxW).coerceIn(0.10f, 0.90f),
        noseToEyeRatio = (noseDrop / eyeDist).coerceIn(0.10f, 1.50f),
        mouthToEyeRatio = (mouthDist / eyeDist).coerceIn(0.20f, 1.60f),
        boxAspectRatio = (boxW / boxH).coerceIn(0.40f, 1.50f),
        philtrumToEyeRatio = (philtrumDist / eyeDist).coerceIn(0.05f, 0.90f),
        jawChinDropRatio = (jawChinDrop / eyeDist).coerceIn(0.20f, 1.60f),
        jawToCheekWidthRatio = jawWidthToCheek,
        symmetryBalanceRatio = symmetryRatio
    )

    return FaceAnalysisFrame(
        faceDetected = true,
        eyesLocked = true,
        noseMustacheLocked = true,
        jawBeardLocked = true,
        signature = signature,
        livenessOpenEyes = true,
        headPoseAligned = true,
        headYawDeg = yaw,
        headPitchDeg = pitch,
        headRollDeg = roll,
        isFrontCamera = isFrontCamera,
        selfieImagePath = capturedSelfiePath,
        qualityIssue = null
    )
}

internal fun extractFaceSignature(face: Face): String? = analyzeFaceBiometrics(face).signature
