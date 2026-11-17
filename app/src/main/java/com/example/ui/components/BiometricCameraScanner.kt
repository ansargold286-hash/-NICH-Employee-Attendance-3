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
                                            if (lastSelfiePathRef.get() == null || nowMs - lastSelfieWriteMs.get() >= 80L) {
                                                val capturedPath = captureRotatedSelfieFile(
                                                    context = appContext,
                                                    imageProxy = imageProxy,
                                                    isFrontCamera = frontCam,
                                                    rotationDegrees = rotationDeg,
                                                    maxKeepCount = 8
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
                                                                capturedSelfiePath = selfieForFrame,
                                                                frameWidth = inputImage.width,
                                                                frameHeight = inputImage.height
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

internal fun captureRotatedSelfieFile(
    context: Context,
    imageProxy: ImageProxy,
    isFrontCamera: Boolean,
    rotationDegrees: Int,
    targetDirName: String = "live_selfies",
    filePrefix: String = "selfie",
    maxKeepCount: Int = 4
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

        val dir = File(context.cacheDir, targetDirName).apply {
            if (!exists()) mkdirs()
        }
        if (maxKeepCount > 0) {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(maxKeepCount)?.forEach { old ->
                runCatching { old.delete() }
            }
        }
        val outFile = File(dir, "${filePrefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(outFile).use { stream ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
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
 * Extracts a 16-dimensional scale- and lighting-invariant facial geometry & contour vector
 * covering inter-ocular spacing, eye aspect ratios, eyebrow arch lift, nose bridge & base width,
 * philtrum, mouth width & lip thickness, cheekbone span, gonial jaw taper, chin drop, and symmetry.
 */
internal fun analyzeFaceBiometrics(
    face: Face,
    targetDirection: LivenessDirection = LivenessDirection.CENTER_VERIFY,
    isFrontCamera: Boolean = true,
    capturedSelfiePath: String? = null,
    frameWidth: Int = 0,
    frameHeight: Int = 0
): FaceAnalysisFrame {
    val box: Rect = face.boundingBox
    val boxW = box.width().toFloat().coerceAtLeast(1f)
    val boxH = box.height().toFloat().coerceAtLeast(1f)
    val centerXNorm = if (frameWidth > 0) (box.exactCenterX() / frameWidth.toFloat()).coerceIn(0f, 1f) else null
    val centerYNorm = if (frameHeight > 0) (box.exactCenterY() / frameHeight.toFloat()).coerceIn(0f, 1f) else null

    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
    val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
    val leftCheek = face.getLandmark(FaceLandmark.LEFT_CHEEK)?.position
    val rightCheek = face.getLandmark(FaceLandmark.RIGHT_CHEEK)?.position

    val faceContour = face.getContour(FaceContour.FACE)?.points.orEmpty()
    val leftEyeContour = face.getContour(FaceContour.LEFT_EYE)?.points.orEmpty()
    val rightEyeContour = face.getContour(FaceContour.RIGHT_EYE)?.points.orEmpty()
    val leftBrowContour = face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points.orEmpty()
    val rightBrowContour = face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points.orEmpty()
    val noseBottomContour = face.getContour(FaceContour.NOSE_BOTTOM)?.points.orEmpty()
    val upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP)?.points.orEmpty()
    val upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty()

    val eyesLocked = leftEye != null && rightEye != null
    val noseMustacheLocked = nose != null && mouthLeft != null && mouthRight != null
    val jawBeardLocked = faceContour.size >= 19 || (mouthBottom != null && (leftCheek != null || rightCheek != null))

    val yaw = face.headEulerAngleY
    val pitch = face.headEulerAngleX
    val roll = face.headEulerAngleZ

    val leftOpenProb = face.leftEyeOpenProbability
    val rightOpenProb = face.rightEyeOpenProbability
    val smilingProb = face.smilingProbability
    val livenessOpenEyes = (leftOpenProb == null || leftOpenProb >= 0.25f) &&
        (rightOpenProb == null || rightOpenProb >= 0.25f)

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
            leftEyeOpenProbability = leftOpenProb,
            rightEyeOpenProbability = rightOpenProb,
            smilingProbability = smilingProb,
            faceCenterXNorm = centerXNorm,
            faceCenterYNorm = centerYNorm,
            isFrontCamera = isFrontCamera,
            selfieImagePath = null,
            qualityIssue = if (directionMatched) null else targetDirection.instruction
        )
    }

    val headPoseAligned = abs(yaw) <= 25f && abs(pitch) <= 25f && abs(roll) <= 25f

    if (!eyesLocked || nose == null) {
        return FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = eyesLocked,
            noseMustacheLocked = noseMustacheLocked,
            jawBeardLocked = jawBeardLocked,
            signature = null,
            livenessOpenEyes = livenessOpenEyes,
            headPoseAligned = headPoseAligned,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            headRollDeg = roll,
            leftEyeOpenProbability = leftOpenProb,
            rightEyeOpenProbability = rightOpenProb,
            smilingProbability = smilingProb,
            faceCenterXNorm = centerXNorm,
            faceCenterYNorm = centerYNorm,
            isFrontCamera = isFrontCamera,
            selfieImagePath = capturedSelfiePath,
            qualityIssue = "Look straight into the camera so both eyes and face are clearly visible"
        )
    }

    val lEye = leftEye!!
    val rEye = rightEye!!
    val eyeDist = hypot(rEye.x - lEye.x, rEye.y - lEye.y).coerceAtLeast(1f)
    val eyeMidX = (lEye.x + rEye.x) / 2f
    val eyeMidY = (lEye.y + rEye.y) / 2f

    val mLeftX = mouthLeft?.x ?: (box.left + boxW * 0.34f)
    val mLeftY = mouthLeft?.y ?: (box.top + boxH * 0.74f)
    val mRightX = mouthRight?.x ?: (box.left + boxW * 0.66f)
    val mRightY = mouthRight?.y ?: (box.top + boxH * 0.74f)

    val mouthDist = hypot(mRightX - mLeftX, mRightY - mLeftY)
    val noseDrop = hypot(nose.x - eyeMidX, nose.y - eyeMidY)
    val mouthCenterX = (mLeftX + mRightX) / 2f
    val mouthCenterY = (mLeftY + mRightY) / 2f

    val upperLipMid = if (upperLipTop.isNotEmpty()) upperLipTop[upperLipTop.size / 2] else null
    val lowerUpperLipMid = if (upperLipBottom.isNotEmpty()) upperLipBottom[upperLipBottom.size / 2] else null
    val philtrumDist = if (upperLipMid != null) {
        hypot(upperLipMid.x - nose.x, upperLipMid.y - nose.y)
    } else {
        hypot(mouthCenterX - nose.x, mouthCenterY - nose.y) * 0.65f
    }

    val chinPoint = if (faceContour.size >= 19) faceContour.maxByOrNull { it.y } else null
    val jawChinDrop = when {
        chinPoint != null -> abs(chinPoint.y - mouthCenterY)
        mouthBottom != null -> abs(box.bottom.toFloat() - mouthBottom.y)
        else -> abs(box.bottom.toFloat() - mouthCenterY)
    }

    val cheekSpan = when {
        faceContour.size >= 30 -> hypot(
            faceContour[6].x - faceContour[30].x,
            faceContour[6].y - faceContour[30].y
        ).coerceAtLeast(1f)
        leftCheek != null && rightCheek != null -> hypot(
            rightCheek.x - leftCheek.x,
            rightCheek.y - leftCheek.y
        ).coerceAtLeast(1f)
        else -> boxW * 0.84f
    }

    val jawWidthToCheek = if (faceContour.size >= 30) {
        val lowerJawSpan = hypot(
            faceContour[12].x - faceContour[24].x,
            faceContour[12].y - faceContour[24].y
        )
        (lowerJawSpan / cheekSpan).coerceIn(0.35f, 1.15f)
    } else {
        0.72f
    }

    val noseToLeftEye = hypot(nose.x - lEye.x, nose.y - lEye.y).coerceAtLeast(1f)
    val noseToRightEye = hypot(nose.x - rEye.x, nose.y - rEye.y).coerceAtLeast(1f)
    val symmetryRatio = (minOf(noseToLeftEye, noseToRightEye) / maxOf(noseToLeftEye, noseToRightEye))
        .coerceIn(0.5f, 1.0f)

    // Extra 8 high-discrimination facial structure & contour features (total = 16 dims)
    val contourMinX = faceContour.minOfOrNull { it.x } ?: box.left.toFloat()
    val contourMaxX = faceContour.maxOfOrNull { it.x } ?: box.right.toFloat()
    val contourMinY = faceContour.minOfOrNull { it.y } ?: box.top.toFloat()
    val contourMaxY = faceContour.maxOfOrNull { it.y } ?: box.bottom.toFloat()
    val contourAspect = ((contourMaxX - contourMinX).coerceAtLeast(1f) /
        (contourMaxY - contourMinY).coerceAtLeast(1f)).coerceIn(0.45f, 1.35f)

    val leftEyeAspect = computeEyeContourAspect(leftEyeContour)
    val rightEyeAspect = computeEyeContourAspect(rightEyeContour)

    val noseBaseSpan = if (noseBottomContour.size >= 3) {
        hypot(
            noseBottomContour.last().x - noseBottomContour.first().x,
            noseBottomContour.last().y - noseBottomContour.first().y
        )
    } else {
        eyeDist * 0.56f
    }

    val chinTaperSpan = if (faceContour.size >= 22) {
        hypot(faceContour[15].x - faceContour[21].x, faceContour[15].y - faceContour[21].y)
    } else {
        cheekSpan * 0.45f
    }

    val browLift = if (leftBrowContour.isNotEmpty() && rightBrowContour.isNotEmpty()) {
        val leftBrowMidY = leftBrowContour[leftBrowContour.size / 2].y
        val rightBrowMidY = rightBrowContour[rightBrowContour.size / 2].y
        ((lEye.y - leftBrowMidY) + (rEye.y - rightBrowMidY)) / 2f
    } else {
        eyeDist * 0.28f
    }

    val upperLipThickness = if (upperLipMid != null && lowerUpperLipMid != null) {
        hypot(lowerUpperLipMid.x - upperLipMid.x, lowerUpperLipMid.y - upperLipMid.y)
    } else {
        mouthDist * 0.14f
    }

    val features16 = listOf(
        (eyeDist / boxW).coerceIn(0.10f, 0.90f),
        (noseDrop / eyeDist).coerceIn(0.10f, 1.50f),
        (mouthDist / eyeDist).coerceIn(0.20f, 1.60f),
        (boxW / boxH).coerceIn(0.40f, 1.50f),
        (philtrumDist / eyeDist).coerceIn(0.05f, 0.90f),
        (jawChinDrop / eyeDist).coerceIn(0.20f, 1.60f),
        jawWidthToCheek,
        symmetryRatio,
        contourAspect,
        leftEyeAspect,
        rightEyeAspect,
        (noseBaseSpan / noseDrop.coerceAtLeast(1f)).coerceIn(0.20f, 1.40f),
        (cheekSpan / eyeDist).coerceIn(1.20f, 3.00f),
        (chinTaperSpan / cheekSpan).coerceIn(0.20f, 0.95f),
        (browLift / eyeDist).coerceIn(0.05f, 0.80f),
        (upperLipThickness / mouthDist.coerceAtLeast(1f)).coerceIn(0.03f, 0.45f)
    )

    val signature = FaceBiometricMatcher.encodeVectorSignature(features16)

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
        leftEyeOpenProbability = leftOpenProb,
        rightEyeOpenProbability = rightOpenProb,
        smilingProbability = smilingProb,
        faceCenterXNorm = centerXNorm,
        faceCenterYNorm = centerYNorm,
        isFrontCamera = isFrontCamera,
        selfieImagePath = capturedSelfiePath,
        qualityIssue = if (headPoseAligned) null else "Face camera directly (keep head level)"
    )
}

private fun computeEyeContourAspect(points: List<android.graphics.PointF>): Float {
    if (points.size < 6) return 0.34f
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val width = (maxX - minX).coerceAtLeast(1f)
    val height = (maxY - minY).coerceAtLeast(0.5f)
    return (height / width).coerceIn(0.10f, 0.90f)
}

internal fun extractFaceSignature(face: Face): String? = analyzeFaceBiometrics(face).signature
