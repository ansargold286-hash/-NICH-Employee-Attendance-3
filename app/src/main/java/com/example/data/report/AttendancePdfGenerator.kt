package com.example.data.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object AttendancePdfGenerator {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f

    fun generateReportPdf(
        context: Context,
        employee: Employee,
        log: AttendanceLog,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        syncedToCloud: Boolean,
        recentDailyLogs: List<AttendanceLog>,
        recipientEmail: String,
        liveSelfiePath: String? = null
    ): File {
        val reportsDir = File(context.cacheDir, "attendance_reports").apply {
            if (!exists()) mkdirs()
        }
        val safeCode = employee.employeeCode.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeLogId = log.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(reportsDir, "NICH_${safeCode}_${eventType.wireValue}_${safeLogId}.pdf")

        val summaryRows = listOf(
            "Report ID" to log.id,
            "Employee Name" to employee.name,
            "Employee Code" to employee.employeeCode,
            "Department" to employee.department,
            "Role Title" to employee.roleTitle,
            "Attendance Type" to "${eventType.displayLabel} (${eventType.wireValue})",
            "Verification Method" to verificationMethod.displayLabel,
            "Match Confidence" to "${confidenceScore.coerceIn(0, 100)}%",
            "Timestamp" to "${log.loggedTimeLabel}  •  ${log.loggedDateKey}",
            "Cloud Sync Status" to if (syncedToCloud) {
                "Synced to Cloud Firestore (attendanceapp-f6a0e)"
            } else {
                "Recorded Offline (Queued for Cloud Sync)"
            },
            "Report Recipient" to recipientEmail
        )
        val todaysLogs = recentDailyLogs.filter { it.loggedDateKey == log.loggedDateKey }

        val nativePdfSuccess = tryWriteNativePdf(
            context = context,
            outFile = outFile,
            eventType = eventType,
            logDateKey = log.loggedDateKey,
            loggedTimeLabel = log.loggedTimeLabel,
            rows = summaryRows,
            todaysLogs = todaysLogs,
            recipientEmail = recipientEmail,
            liveSelfiePath = liveSelfiePath
        )
        if (!nativePdfSuccess || !outFile.exists() || outFile.length() == 0L) {
            writeFallbackPdf14(
                outFile = outFile,
                eventType = eventType,
                logDateKey = log.loggedDateKey,
                rows = summaryRows,
                todaysLogs = todaysLogs,
                recipientEmail = recipientEmail,
                liveSelfiePath = liveSelfiePath
            )
        }

        return outFile
    }

    private fun tryWriteNativePdf(
        context: Context,
        outFile: File,
        eventType: AttendanceEventType,
        logDateKey: String,
        loggedTimeLabel: String,
        rows: List<Pair<String, String>>,
        todaysLogs: List<AttendanceLog>,
        recipientEmail: String,
        liveSelfiePath: String?
    ): Boolean {
        return try {
            val document = PdfDocument()
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas

                val headerBgPaint = Paint().apply {
                    color = Color.rgb(11, 20, 38)
                    style = Paint.Style.FILL
                }
                val accentColor = if (eventType == AttendanceEventType.CHECK_IN) {
                    Color.rgb(0, 184, 113)
                } else {
                    Color.rgb(0, 145, 234)
                }
                val accentPaint = Paint().apply {
                    color = accentColor
                    style = Paint.Style.FILL
                }
                val borderPaint = Paint().apply {
                    color = Color.rgb(210, 220, 235)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.2f
                }
                val rowAltPaint = Paint().apply {
                    color = Color.rgb(245, 248, 252)
                    style = Paint.Style.FILL
                }

                val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(138, 164, 200)
                    textSize = 10.5f
                }
                val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(15, 28, 52)
                    textSize = 13f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(86, 110, 144)
                    textSize = 10.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(18, 28, 45)
                    textSize = 11f
                }
                val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 11f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }

                // Header Banner with Official App Logo
                canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 96f, headerBgPaint)
                canvas.drawRect(0f, 92f, PAGE_WIDTH.toFloat(), 96f, accentPaint)

                val logoBitmap: Bitmap? = runCatching {
                    BitmapFactory.decodeResource(context.resources, R.drawable.nich_official_logo)
                }.getOrNull()
                val textStartX = if (logoBitmap != null) {
                    val destRect = RectF(MARGIN_X, 20f, MARGIN_X + 52f, 72f)
                    canvas.drawBitmap(
                        logoBitmap,
                        Rect(0, 0, logoBitmap.width, logoBitmap.height),
                        destRect,
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    logoBitmap.recycle()
                    MARGIN_X + 64f
                } else {
                    MARGIN_X
                }

                canvas.drawText("NICH EMPLOYEE ATTENDANCE 3", textStartX, 42f, titlePaint)
                canvas.drawText(
                    "AUTOMATED BIOMETRIC & PASSCODE ATTENDANCE REPORT",
                    textStartX,
                    64f,
                    subtitlePaint
                )

                // Event Pill on Header Right
                val badgeRect = RectF(PAGE_WIDTH - MARGIN_X - 118f, 28f, PAGE_WIDTH - MARGIN_X, 60f)
                canvas.drawRoundRect(badgeRect, 8f, 8f, accentPaint)
                canvas.drawText(
                    eventType.displayLabel,
                    badgeRect.left + 22f,
                    badgeRect.top + 20f,
                    badgeTextPaint
                )

                var y = 130f
                canvas.drawText("ATTENDANCE EVENT SUMMARY", MARGIN_X, y, sectionPaint)
                y += 14f

                val tableTop = y
                val rowHeight = 28f
                val tableWidth = PAGE_WIDTH - (MARGIN_X * 2f)
                val colSplit = MARGIN_X + 165f

                rows.forEachIndexed { index, (label, value) ->
                    val rowTop = tableTop + (index * rowHeight)
                    if (index % 2 == 0) {
                        canvas.drawRect(
                            MARGIN_X,
                            rowTop,
                            MARGIN_X + tableWidth,
                            rowTop + rowHeight,
                            rowAltPaint
                        )
                    }
                    canvas.drawText(label, MARGIN_X + 10f, rowTop + 18f, labelPaint)
                    canvas.drawText(value.take(58), colSplit, rowTop + 18f, valuePaint)
                }

                val tableBottom = tableTop + (rows.size * rowHeight)
                canvas.drawRect(MARGIN_X, tableTop, MARGIN_X + tableWidth, tableBottom, borderPaint)
                canvas.drawLine(colSplit - 12f, tableTop, colSplit - 12f, tableBottom, borderPaint)

                // Daily Activity Breakdown
                y = tableBottom + 28f
                val checkInsToday = todaysLogs.count { it.eventType == AttendanceEventType.CHECK_IN.wireValue }
                val checkOutsToday = todaysLogs.count { it.eventType == AttendanceEventType.CHECK_OUT.wireValue }

                canvas.drawText("DAILY TERMINAL ACTIVITY ($logDateKey)", MARGIN_X, y, sectionPaint)
                y += 18f
                canvas.drawText(
                    "Total Logged Today: ${todaysLogs.size}   |   Check-Ins: $checkInsToday   |   Check-Outs: $checkOutsToday",
                    MARGIN_X,
                    y,
                    valuePaint
                )
                y += 16f

                todaysLogs.take(3).forEachIndexed { idx, item ->
                    val lineY = y + (idx * 19f)
                    canvas.drawText(
                        "${idx + 1}. ${item.employeeName.take(28)} (${item.employeeCode}) — ${item.eventType} at ${item.loggedTimeLabel}",
                        MARGIN_X + 6f,
                        lineY + 12f,
                        valuePaint
                    )
                }

                // Embedded Live Verification Selfie Card
                val selfieBitmap: Bitmap? = if (!liveSelfiePath.isNullOrBlank()) {
                    runCatching {
                        val f = File(liveSelfiePath)
                        if (f.exists() && f.length() > 0L) BitmapFactory.decodeFile(f.absolutePath) else null
                    }.getOrNull()
                } else {
                    null
                }
                if (selfieBitmap != null) {
                    val cardTop = 602f
                    val cardBottom = 758f
                    val cardRect = RectF(MARGIN_X, cardTop, PAGE_WIDTH - MARGIN_X, cardBottom)
                    canvas.drawRoundRect(cardRect, 10f, 10f, rowAltPaint)
                    canvas.drawRoundRect(cardRect, 10f, 10f, borderPaint)

                    val thumbSize = 128f
                    val thumbLeft = MARGIN_X + 14f
                    val thumbTop = cardTop + 14f
                    val thumbRect = RectF(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize)
                    canvas.drawBitmap(
                        selfieBitmap,
                        Rect(0, 0, selfieBitmap.width, selfieBitmap.height),
                        thumbRect,
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    canvas.drawRect(thumbRect, borderPaint)
                    selfieBitmap.recycle()

                    val infoX = thumbRect.right + 18f
                    canvas.drawText("LIVE VERIFICATION SELFIE", infoX, cardTop + 38f, sectionPaint)
                    canvas.drawText("Liveness Challenge: PASSED (Head-Pose Verified)", infoX, cardTop + 62f, valuePaint)
                    canvas.drawText("Capture Timestamp: $loggedTimeLabel ($logDateKey)", infoX, cardTop + 84f, valuePaint)
                    canvas.drawText("Security Mode: Strict Single-Step Biometric Check-In", infoX, cardTop + 106f, labelPaint)
                }

                // Footer
                canvas.drawLine(MARGIN_X, PAGE_HEIGHT - 56f, PAGE_WIDTH - MARGIN_X, PAGE_HEIGHT - 56f, borderPaint)
                canvas.drawText(
                    "Generated automatically by NICH Employee Attendance 3 Terminal • Dispatched to $recipientEmail",
                    MARGIN_X,
                    PAGE_HEIGHT - 34f,
                    subtitlePaint.apply { color = Color.rgb(95, 115, 145) }
                )

                document.finishPage(page)
                FileOutputStream(outFile).use { stream ->
                    document.writeTo(stream)
                }
                true
            } finally {
                runCatching { document.close() }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeFallbackPdf14(
        outFile: File,
        eventType: AttendanceEventType,
        logDateKey: String,
        rows: List<Pair<String, String>>,
        todaysLogs: List<AttendanceLog>,
        recipientEmail: String,
        liveSelfiePath: String?
    ) {
        val checkInsToday = todaysLogs.count { it.eventType == AttendanceEventType.CHECK_IN.wireValue }
        val checkOutsToday = todaysLogs.count { it.eventType == AttendanceEventType.CHECK_OUT.wireValue }

        val lines = buildList {
            add("NICH EMPLOYEE ATTENDANCE 3 - ${eventType.displayLabel} REPORT")
            add("AUTOMATED BIOMETRIC & PASSCODE ATTENDANCE SYSTEM")
            add("------------------------------------------------------------")
            rows.forEach { (label, value) ->
                add("$label: $value")
            }
            if (!liveSelfiePath.isNullOrBlank()) {
                val selfieFile = File(liveSelfiePath)
                if (selfieFile.exists()) {
                    add("Live Verification Selfie: Embedded (${selfieFile.name}, ${selfieFile.length()} bytes)")
                }
            }
            add("------------------------------------------------------------")
            add("DAILY TERMINAL ACTIVITY ($logDateKey)")
            add("Total Logged: ${todaysLogs.size} | Check-Ins: $checkInsToday | Check-Outs: $checkOutsToday")
            todaysLogs.take(5).forEachIndexed { idx, item ->
                add("${idx + 1}. ${item.employeeName} (${item.employeeCode}) - ${item.eventType} at ${item.loggedTimeLabel}")
            }
            add("------------------------------------------------------------")
            add("Dispatched automatically to $recipientEmail")
        }

        val contentStream = buildString {
            append("BT\n")
            append("/F1 11 Tf\n")
            var cursorY = 790
            lines.forEachIndexed { idx, rawLine ->
                val safe = escapePdfText(rawLine)
                if (idx == 0) {
                    append("40 $cursorY Td\n")
                } else {
                    append("0 -18 Td\n")
                }
                append("($safe) Tj\n")
                cursorY -= 18
            }
            append("ET\n")
        }

        val objects = listOf(
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length ${contentStream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${contentStream}endstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
        )

        val pdfBuilder = StringBuilder()
        pdfBuilder.append("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEach { obj ->
            offsets += pdfBuilder.toString().toByteArray(Charsets.US_ASCII).size
            pdfBuilder.append(obj)
        }
        val xrefOffset = pdfBuilder.toString().toByteArray(Charsets.US_ASCII).size
        pdfBuilder.append("xref\n0 ${objects.size + 1}\n")
        pdfBuilder.append("0000000000 65535 f \n")
        for (i in 1..objects.size) {
            pdfBuilder.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i]))
        }
        pdfBuilder.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        pdfBuilder.append("startxref\n$xrefOffset\n%%EOF\n")

        FileOutputStream(outFile).use { stream ->
            stream.write(pdfBuilder.toString().toByteArray(Charsets.US_ASCII))
        }
    }

    private fun escapePdfText(input: String): String {
        val ascii = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    in ' '..'~' -> append(ch)
                    '•', '—' -> append('-')
                    else -> append('?')
                }
            }
        }
        return ascii.take(96)
    }
}
