package com.example.data.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.BidiTextHelper
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
            "Employee Name" to BidiTextHelper.formatEmployeeHeaderTitle(employee.name, employee.employeeCode),
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
            employeeName = employee.name,
            employeeCode = employee.employeeCode,
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
        employeeName: String,
        employeeCode: String,
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
                val valueWidth = (MARGIN_X + tableWidth) - colSplit - 8f

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
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = label,
                        x = MARGIN_X + 10f,
                        yTop = rowTop,
                        maxWidth = (colSplit - MARGIN_X - 24f),
                        maxHeight = rowHeight,
                        paint = labelPaint
                    )
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = value,
                        x = colSplit,
                        yTop = rowTop,
                        maxWidth = valueWidth,
                        maxHeight = rowHeight,
                        paint = valuePaint
                    )
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
                    val lineY = y + (idx * 20f)
                    val formattedTitle = BidiTextHelper.formatEmployeeHeaderTitle(item.employeeName, item.employeeCode)
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = "${idx + 1}. $formattedTitle — ${item.eventType} at ${item.loggedTimeLabel}",
                        x = MARGIN_X + 6f,
                        yTop = lineY,
                        maxWidth = tableWidth - 12f,
                        maxHeight = 20f,
                        paint = valuePaint
                    )
                }

                // Embedded Live Verification Selfie Card
                val selfieBitmap: Bitmap? = decodeSampledBitmap(liveSelfiePath, 256)
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
                    val infoWidth = (PAGE_WIDTH - MARGIN_X - 12f) - infoX
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = "LIVE VERIFICATION SELFIE",
                        x = infoX,
                        yTop = cardTop + 20f,
                        maxWidth = infoWidth,
                        maxHeight = 22f,
                        paint = sectionPaint
                    )
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = "Staff: ${BidiTextHelper.formatEmployeeHeaderTitle(employeeName, employeeCode)}",
                        x = infoX,
                        yTop = cardTop + 44f,
                        maxWidth = infoWidth,
                        maxHeight = 20f,
                        paint = valuePaint
                    )
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = "Status: ${eventType.displayLabel} VERIFIED (Liveness Passed)",
                        x = infoX,
                        yTop = cardTop + 66f,
                        maxWidth = infoWidth,
                        maxHeight = 20f,
                        paint = valuePaint
                    )
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = "Timestamp: $loggedTimeLabel ($logDateKey)",
                        x = infoX,
                        yTop = cardTop + 88f,
                        maxWidth = infoWidth,
                        maxHeight = 20f,
                        paint = valuePaint
                    )
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = "Security: Flexible 1% Biometric Selfie Capture",
                        x = infoX,
                        yTop = cardTop + 112f,
                        maxWidth = infoWidth,
                        maxHeight = 20f,
                        paint = labelPaint
                    )
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

    /**
     * Renders mixed English/Urdu text safely with directional isolates and strict clip bounds
     * so RTL words never flip LTR parentheses, hyphens, or timestamps and never overlap adjacent columns.
     */
    private fun drawBidiBoundedText(
        canvas: Canvas,
        text: String,
        x: Float,
        yTop: Float,
        maxWidth: Float,
        maxHeight: Float,
        paint: TextPaint,
        maxLines: Int = 1
    ) {
        if (text.isBlank()) return
        val hasIsolates = text.indexOf('\u2066') >= 0 || text.indexOf('\u2067') >= 0
        val prepared = if (hasIsolates) text else BidiTextHelper.isolateMixedText(text)
        val widthPx = maxWidth.toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(prepared, 0, prepared.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.LTR)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        val verticalOffset = ((maxHeight - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(x, yTop + verticalOffset)
        canvas.clipRect(0f, -2f, maxWidth, maxHeight)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun decodeSampledBitmap(path: String?, reqSizePx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sampleSize = 1
            val maxDim = maxOf(opts.outWidth, opts.outHeight)
            while (maxDim / sampleSize > reqSizePx * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        }.getOrNull()
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
                add("$label: ${BidiTextHelper.stripBidiControls(value)}")
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
                add("${idx + 1}. ${BidiTextHelper.stripBidiControls(item.employeeName)} (${item.employeeCode}) - ${item.eventType} at ${item.loggedTimeLabel}")
            }
            add("------------------------------------------------------------")
            add("Dispatched automatically to $recipientEmail")
        }

        writeSimpleAsciiLinesPdf(outFile, lines)
    }

    private fun writeSimpleAsciiLinesPdf(outFile: File, lines: List<String>) {
        val maxLinesPerDoc = lines.take(40)
        val contentStream = buildString {
            append("BT\n")
            append("/F1 10 Tf\n")
            var cursorY = 795
            maxLinesPerDoc.forEachIndexed { idx, rawLine ->
                val safe = escapePdfText(rawLine)
                if (idx == 0) {
                    append("36 $cursorY Td\n")
                } else {
                    append("0 -17 Td\n")
                }
                append("($safe) Tj\n")
                cursorY -= 17
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
        val stripped = BidiTextHelper.stripBidiControls(input)
        val ascii = buildString(stripped.length) {
            for (ch in stripped) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '(' -> append("\\(")
                    ')' -> append("\\)")
                    in ' '..'~' -> append(ch)
                    '•', '—' -> append('-')
                    else -> if (ch.code > 0x007F) append('?') else Unit
                }
            }
        }
        return ascii.take(104)
    }
}
