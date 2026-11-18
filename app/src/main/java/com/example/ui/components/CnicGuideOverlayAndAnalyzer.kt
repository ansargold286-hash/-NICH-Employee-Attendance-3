package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.example.data.model.CnicOcrParseResult
import com.example.data.model.CnicOcrParser
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.google.mlkit.vision.text.Text
import java.io.File
import java.io.FileOutputStream

/**
 * Draws the real-time Green Guide Frame (ID-1 card 1.586:1 aspect ratio) used
 * for automated rear-camera CNIC Front-Side capture in both Registration and Step-B verification.
 */
internal fun DrawScope.drawGreenCnicGuideFrame(
    isAligned: Boolean,
    sweepFraction: Float
) {
    val w = size.width
    val h = size.height
    val cardW = minOf(w * 0.86f, h * 0.72f)
    val cardH = (cardW / 1.586f).coerceAtMost(h * 0.50f)
    val left = (w - cardW) / 2f
    val top = (h - cardH) * 0.48f
    val right = left + cardW
    val bottom = top + cardH
    val cornerRad = 18.dp.toPx()

    // Dim everything outside the Green Guide window
    val cutoutPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, right, bottom),
                cornerRadius = CornerRadius(cornerRad, cornerRad)
            )
        )
    }
    clipPath(cutoutPath, clipOp = ClipOp.Difference) {
        drawRect(color = Color.Black.copy(alpha = 0.54f))
    }

    // Subtle green fill glow when card aligns inside the frame
    if (isAligned) {
        drawRoundRect(
            color = NeonGreen.copy(alpha = 0.14f),
            topLeft = Offset(left, top),
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(cornerRad, cornerRad)
        )
    }

    // Full green rectangular perimeter
    val frameColor = if (isAligned) NeonGreen else NeonGreen.copy(alpha = 0.78f)
    drawRoundRect(
        color = frameColor,
        topLeft = Offset(left, top),
        size = Size(cardW, cardH),
        cornerRadius = CornerRadius(cornerRad, cornerRad),
        style = Stroke(width = if (isAligned) 3.4.dp.toPx() else 2.2.dp.toPx())
    )

    // Animated green OCR sweep line inside the CNIC guide window
    val beamY = top + (cardH * sweepFraction.coerceIn(0.08f, 0.92f))
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, NeonGreen, NeonCyan, NeonGreen, Color.Transparent)
        ),
        start = Offset(left + 12.dp.toPx(), beamY),
        end = Offset(right - 12.dp.toPx(), beamY),
        strokeWidth = 2.4.dp.toPx(),
        cap = StrokeCap.Round
    )

    // Crisp corner L-brackets framing the Green Guide box
    val cornerLen = 28.dp.toPx()
    val bracketStroke = 4.2.dp.toPx()
    val brackets = listOf(
        Triple(Offset(left, top + cornerLen), Offset(left, top), Offset(left + cornerLen, top)),
        Triple(Offset(right - cornerLen, top), Offset(right, top), Offset(right, top + cornerLen)),
        Triple(Offset(left, bottom - cornerLen), Offset(left, bottom), Offset(left + cornerLen, bottom)),
        Triple(Offset(right - cornerLen, bottom), Offset(right, bottom), Offset(right, bottom - cornerLen))
    )
    brackets.forEach { (startPt, cornerPt, endPt) ->
        val p = Path().apply {
            moveTo(startPt.x, startPt.y)
            lineTo(cornerPt.x, cornerPt.y)
            lineTo(endPt.x, endPt.y)
        }
        drawPath(
            path = p,
            color = NeonGreen,
            style = Stroke(width = bracketStroke, cap = StrokeCap.Square, join = StrokeJoin.Miter)
        )
    }
}

internal fun evaluateVisionTextInGreenFrame(
    visionText: Text,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int
): Pair<Boolean, CnicOcrParseResult> {
    val rotW = if (rotationDegrees == 90 || rotationDegrees == 270) imageHeight else imageWidth
    val rotH = if (rotationDegrees == 90 || rotationDegrees == 270) imageWidth else imageHeight
    val blocks = visionText.textBlocks

    if (blocks.isEmpty() || rotW <= 0 || rotH <= 0) {
        return false to CnicOcrParser.parseFrontOcrText("", insideGreenFrame = false)
    }

    var alignedBlocks = 0
    for (block in blocks) {
        val box = block.boundingBox ?: continue
        val cxNorm = box.exactCenterX() / rotW.toFloat()
        val cyNorm = box.exactCenterY() / rotH.toFloat()
        if (cxNorm in 0.06f..0.94f && cyNorm in 0.16f..0.84f) {
            alignedBlocks++
        }
    }

    val insideFrame = alignedBlocks >= 2 || (alignedBlocks >= 1 && blocks.size == 1)
    val parsed = CnicOcrParser.parseFrontOcrText(
        rawText = visionText.text,
        insideGreenFrame = insideFrame
    )
    return insideFrame to parsed
}

internal fun captureRotatedCnicCardFile(
    context: Context,
    imageProxy: ImageProxy,
    isFrontCamera: Boolean,
    rotationDegrees: Int,
    targetDirName: String = "live_cnic_captures",
    filePrefix: String = "cnic",
    maxKeepCount: Int = 5
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

        val maxDim = 960
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
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        outFile.absolutePath
    } catch (e: Throwable) {
        Log.w("CnicCapture", "CNIC snapshot failed: ${e.message}")
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
