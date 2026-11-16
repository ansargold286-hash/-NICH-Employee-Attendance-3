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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.FaceBiometricMatcher
import com.example.data.model.LivenessDirection
import com.example.data.model.LivenessEvaluator
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianVoid
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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val ScannerElectricBlue = Color(0xFF29B6F6)
private val ScannerDeepBlue = Color(0xFF1E88E5)
private val ScannerNodeBlue = Color(0xFF80D8FF)

@OptIn(ExperimentalGetImage::class)
@Composable
fun BiometricCameraScanner(
    livenessSteps: List<LivenessDirection>,
    currentLivenessStepIndex: Int,
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

    val infiniteTransition = rememberInfiniteTransition(label = "standaloneTerminalScan")
    val sweepFraction by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanSweep"
    )
    val meshPulse by infiniteTransition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "meshPulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        if (hasCameraPermission) {
            val previewView = remember(context) {
                PreviewView(context).apply {
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

                                        if (stepSnapshot == LivenessDirection.CENTER_VERIFY || lastSelfiePathRef.get() == null) {
                                            val nowMs = System.currentTimeMillis()
                                            if (lastSelfiePathRef.get() == null || nowMs - lastSelfieWriteMs.get() >= 220L) {
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
                                        val selfieForFrame = lastSelfiePathRef.get()

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
                            latestCameraFail("No physical camera sensor detected.")
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
                    .background(ObsidianVoid)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = stringResource(R.string.camera_permission_required),
                    tint = ScannerElectricBlue,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ScannerElectricBlue,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = stringResource(R.string.grant_camera_permission),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Central Precision Blue Boundary-Box + Continuous 3D Blue Geometric Scanning Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawStandaloneTerminalScannerOverlay(
                sweepFraction = sweepFraction,
                meshPulse = meshPulse
            )
        }

        // Only visible branding element: NICH Employee Attendance 3 app icon cleanly in bottom corner
        NichOfficialLogoBadge(
            size = 54.dp,
            cornerRadius = 14.dp,
            glowBrush = Brush.linearGradient(
                listOf(NeonCyan, ScannerElectricBlue, NeonBlue)
            ),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 20.dp)
        )
    }
}

private fun DrawScope.drawStandaloneTerminalScannerOverlay(
    sweepFraction: Float,
    meshPulse: Float
) {
    val w = size.width
    val h = size.height
    val boxW = minOf(w * 0.72f, h * 0.44f)
    val boxH = minOf(boxW * 1.24f, h * 0.62f)
    val left = (w - boxW) / 2f
    val top = (h - boxH) / 2f
    val right = left + boxW
    val bottom = top + boxH
    val cx = w / 2f
    val cy = h / 2f

    // 1. Subtle holographic radial aura framing the central scanner zone
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                ScannerElectricBlue.copy(alpha = 0.08f * meshPulse),
                Color.Transparent
            ),
            center = Offset(cx, cy),
            radius = maxOf(boxW, boxH) * 0.75f
        ),
        radius = maxOf(boxW, boxH) * 0.75f,
        center = Offset(cx, cy)
    )

    // 2. Continuous Blue Geometric 3D Facial Contour Mesh Overlay
    val rx = boxW * 0.36f
    val ry = boxH * 0.39f
    val meshColorBright = NeonCyan.copy(alpha = 0.56f * meshPulse)
    val meshColorMid = ScannerElectricBlue.copy(alpha = 0.42f * meshPulse)
    val meshColorSoft = ScannerDeepBlue.copy(alpha = 0.28f * meshPulse)
    val strokeThin = 1.1.dp.toPx()
    val strokeMed = 1.5.dp.toPx()

    // Oval boundary rings (3D concentric depth contour)
    drawOval(
        color = meshColorMid,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
        style = Stroke(width = strokeMed)
    )
    drawOval(
        color = meshColorSoft,
        topLeft = Offset(cx - rx * 0.74f, cy - ry * 0.74f),
        size = Size(rx * 1.48f, ry * 1.48f),
        style = Stroke(width = strokeThin)
    )

    // Longitude 3D facial meridian arcs
    val meridianFactors = listOf(-0.68f, -0.36f, 0f, 0.36f, 0.68f)
    meridianFactors.forEach { factor ->
        val path = Path().apply {
            val steps = 28
            for (i in 0..steps) {
                val t = -1f + (2f * i / steps.toFloat())
                val angle = t * (Math.PI / 2.0)
                val xCurve = cos(angle).toFloat()
                val yOffset = sin(angle).toFloat() * ry
                val px = cx + (factor * rx * xCurve)
                val py = cy + yOffset
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(
            path = path,
            color = if (factor == 0f) meshColorBright else meshColorSoft,
            style = Stroke(width = if (factor == 0f) strokeMed else strokeThin)
        )
    }

    // Latitude contour cross-ribs (forehead, brow, eye, cheekbone, lip, jawline)
    val latitudeNormalizedY = listOf(-0.66f, -0.36f, -0.10f, 0.18f, 0.44f, 0.70f)
    latitudeNormalizedY.forEach { ny ->
        val halfSpanX = rx * cos(ny * (Math.PI / 2.0)).toFloat().coerceAtLeast(0.18f)
        val archLift = ry * 0.08f * ny
        val path = Path().apply {
            moveTo(cx - halfSpanX, cy + ny * ry)
            quadraticTo(cx, cy + ny * ry + archLift, cx + halfSpanX, cy + ny * ry)
        }
        drawPath(
            path = path,
            color = meshColorMid,
            style = Stroke(width = strokeThin)
        )
    }

    // Triangulated geometric wireframe points (brow, eyes, nose bridge, mouth, jaw)
    val pTop = Offset(cx, cy - ry * 0.88f)
    val pBrowL = Offset(cx - rx * 0.56f, cy - ry * 0.38f)
    val pBrowC = Offset(cx, cy - ry * 0.30f)
    val pBrowR = Offset(cx + rx * 0.56f, cy - ry * 0.38f)
    val pEyeL = Offset(cx - rx * 0.42f, cy - ry * 0.14f)
    val pEyeR = Offset(cx + rx * 0.42f, cy - ry * 0.14f)
    val pNoseTip = Offset(cx, cy + ry * 0.16f)
    val pCheekL = Offset(cx - rx * 0.72f, cy + ry * 0.12f)
    val pCheekR = Offset(cx + rx * 0.72f, cy + ry * 0.12f)
    val pMouthL = Offset(cx - rx * 0.34f, cy + ry * 0.45f)
    val pMouthC = Offset(cx, cy + ry * 0.48f)
    val pMouthR = Offset(cx + rx * 0.34f, cy + ry * 0.45f)
    val pChin = Offset(cx, cy + ry * 0.90f)

    val wireframePath = Path().apply {
        moveTo(pTop.x, pTop.y)
        lineTo(pBrowL.x, pBrowL.y)
        lineTo(pEyeL.x, pEyeL.y)
        lineTo(pBrowC.x, pBrowC.y)
        lineTo(pEyeR.x, pEyeR.y)
        lineTo(pBrowR.x, pBrowR.y)
        lineTo(pTop.x, pTop.y)

        moveTo(pBrowL.x, pBrowL.y)
        lineTo(pCheekL.x, pCheekL.y)
        lineTo(pEyeL.x, pEyeL.y)
        lineTo(pNoseTip.x, pNoseTip.y)
        lineTo(pEyeR.x, pEyeR.y)
        lineTo(pCheekR.x, pCheekR.y)
        lineTo(pBrowR.x, pBrowR.y)

        moveTo(pCheekL.x, pCheekL.y)
        lineTo(pMouthL.x, pMouthL.y)
        lineTo(pNoseTip.x, pNoseTip.y)
        lineTo(pMouthR.x, pMouthR.y)
        lineTo(pCheekR.x, pCheekR.y)

        moveTo(pMouthL.x, pMouthL.y)
        lineTo(pMouthC.x, pMouthC.y)
        lineTo(pMouthR.x, pMouthR.y)
        lineTo(pChin.x, pChin.y)
        lineTo(pMouthL.x, pMouthL.y)
    }
    drawPath(
        path = wireframePath,
        color = meshColorBright,
        style = Stroke(width = strokeThin, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    val nodes = listOf(
        pTop, pBrowL, pBrowC, pBrowR,
        pEyeL, pEyeR, pNoseTip,
        pCheekL, pCheekR, pMouthL, pMouthC, pMouthR, pChin
    )
    nodes.forEach { node ->
        drawCircle(
            color = ScannerNodeBlue.copy(alpha = 0.85f * meshPulse),
            radius = 2.6.dp.toPx(),
            center = node
        )
    }

    // 3. Continuous volumetric blue scanning sweep plane inside the boundary box
    val beamY = top + (boxH * sweepFraction)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                ScannerElectricBlue.copy(alpha = 0.24f),
                Color.Transparent
            ),
            startY = beamY - 26.dp.toPx(),
            endY = beamY + 26.dp.toPx()
        ),
        topLeft = Offset(left + 6.dp.toPx(), (beamY - 26.dp.toPx()).coerceAtLeast(top)),
        size = Size(boxW - 12.dp.toPx(), 52.dp.toPx())
    )
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, NeonCyan, ScannerElectricBlue, NeonCyan, Color.Transparent)
        ),
        start = Offset(left + 4.dp.toPx(), beamY),
        end = Offset(right - 4.dp.toPx(), beamY),
        strokeWidth = 2.2.dp.toPx(),
        cap = StrokeCap.Round
    )

    // 4. Central Precision Blue Corner Boundary-Box (L-Brackets like physical EZFace terminal)
    val cornerLen = 36.dp.toPx()
    val glowStrokeW = 7.dp.toPx()
    val sharpStrokeW = 3.2.dp.toPx()

    val corners = listOf(
        Offset(left, top + cornerLen) to Offset(left, top) to Offset(left + cornerLen, top),
        Offset(right - cornerLen, top) to Offset(right, top) to Offset(right, top + cornerLen),
        Offset(left, bottom - cornerLen) to Offset(left, bottom) to Offset(left + cornerLen, bottom),
        Offset(right - cornerLen, bottom) to Offset(right, bottom) to Offset(right, bottom - cornerLen)
    )
    corners.forEach { (edgePair, endPt) ->
        val (startPt, cornerPt) = edgePair
        val bracketPath = Path().apply {
            moveTo(startPt.x, startPt.y)
            lineTo(cornerPt.x, cornerPt.y)
            lineTo(endPt.x, endPt.y)
        }
        drawPath(
            path = bracketPath,
            color = ScannerElectricBlue.copy(alpha = 0.30f),
            style = Stroke(width = glowStrokeW, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
        drawPath(
            path = bracketPath,
            color = NeonCyan,
            style = Stroke(width = sharpStrokeW, cap = StrokeCap.Square, join = StrokeJoin.Miter)
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

    val headPoseAligned = abs(yaw) <= 45f && abs(pitch) <= 45f && abs(roll) <= 40f

    // Flexible partial-landmark fallback: synthesize geometric anchor ratios if some landmarks are occluded
    val defaultEyeY = box.top + boxH * 0.36f
    val lEyeX = leftEye?.x ?: (box.left + boxW * 0.32f)
    val lEyeY = leftEye?.y ?: defaultEyeY
    val rEyeX = rightEye?.x ?: (box.left + boxW * 0.68f)
    val rEyeY = rightEye?.y ?: defaultEyeY
    val noseX = nose?.x ?: (box.left + boxW * 0.50f)
    val noseY = nose?.y ?: (box.top + boxH * 0.56f)
    val mLeftX = mouthLeft?.x ?: (box.left + boxW * 0.34f)
    val mLeftY = mouthLeft?.y ?: (box.top + boxH * 0.74f)
    val mRightX = mouthRight?.x ?: (box.left + boxW * 0.66f)
    val mRightY = mouthRight?.y ?: (box.top + boxH * 0.74f)

    val eyeDist = hypot(rEyeX - lEyeX, rEyeY - lEyeY).coerceAtLeast(1f)
    val eyeMidX = (lEyeX + rEyeX) / 2f
    val eyeMidY = (lEyeY + rEyeY) / 2f

    val mouthDist = hypot(mRightX - mLeftX, mRightY - mLeftY)
    val noseDrop = hypot(noseX - eyeMidX, noseY - eyeMidY)

    val upperLipMid = if (upperLipContour.isNotEmpty()) {
        upperLipContour[upperLipContour.size / 2]
    } else {
        null
    }
    val mouthCenterX = (mLeftX + mRightX) / 2f
    val mouthCenterY = (mLeftY + mRightY) / 2f
    val philtrumDist = if (upperLipMid != null) {
        hypot(upperLipMid.x - noseX, upperLipMid.y - noseY)
    } else {
        hypot(mouthCenterX - noseX, mouthCenterY - noseY)
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

    val noseToLeftEye = hypot(noseX - lEyeX, noseY - lEyeY).coerceAtLeast(1f)
    val noseToRightEye = hypot(noseX - rEyeX, noseY - rEyeY).coerceAtLeast(1f)
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
        eyesLocked = eyesLocked,
        noseMustacheLocked = noseMustacheLocked,
        jawBeardLocked = jawBeardLocked,
        signature = signature,
        livenessOpenEyes = livenessOpenEyes,
        headPoseAligned = headPoseAligned,
        headYawDeg = yaw,
        headPitchDeg = pitch,
        headRollDeg = roll,
        isFrontCamera = isFrontCamera,
        selfieImagePath = capturedSelfiePath,
        qualityIssue = null
    )
}

internal fun extractFaceSignature(face: Face): String? = analyzeFaceBiometrics(face).signature
