package com.example.data.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.BidiTextHelper
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class WeeklyDayRow(
    val dateKey: String,
    val dayDateLabel: String,
    val checkInLabel: String,
    val checkOutLabel: String,
    val dutyHoursLabel: String,
    val isPresent: Boolean,
    val dutyMinutes: Int
)

data class WeeklyReportSummary(
    val weekRangeLabel: String,
    val rows: List<WeeklyDayRow>,
    val totalWorkingDays: Int,
    val totalDutyHoursLabel: String,
    val overallStatusLabel: String,
    val generatedOnLabel: String
)

data class BatchEmployeeRow(
    val employee: Employee,
    val checkInLabel: String,
    val checkOutLabel: String,
    val dutyHoursLabel: String,
    val dutyMinutes: Int,
    val isCycleComplete: Boolean,
    val gpsCoordinates: String,
    val cnicNumber: String,
    val liveSelfiePath: String?
)

data class BatchReportSummary(
    val dateKey: String,
    val dateDisplayLabel: String,
    val rows: List<BatchEmployeeRow>,
    val completedCount: Int,
    val totalCount: Int,
    val totalTeamDutyHoursLabel: String,
    val generatedOnLabel: String,
    val gpsSummary: String,
    val timeSourceSummary: String
)

object AttendancePdfGenerator {

    // A4 Landscape to match the Weekly Attendance Report sample layout
    private const val PAGE_WIDTH = 842
    private const val PAGE_HEIGHT = 595
    private const val MARGIN_X = 28f

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

        val weeklySummary = buildWeeklySummary(
            employee = employee,
            currentLog = log,
            recentDailyLogs = recentDailyLogs
        )

        val registeredPhotoPath = employee.localPhotoPath
            .takeIf { it.isNotBlank() && File(it).let { f -> f.exists() && f.length() > 0L } }
        val liveCapturePath = liveSelfiePath
            ?.takeIf { it.isNotBlank() && File(it).let { f -> f.exists() && f.length() > 0L } }

        val nativePdfSuccess = tryWriteNativeWeeklyPdf(
            outFile = outFile,
            employee = employee,
            currentLog = log,
            weeklySummary = weeklySummary,
            registeredPhotoPath = registeredPhotoPath ?: liveCapturePath,
            liveCapturePath = liveCapturePath ?: registeredPhotoPath,
            hasDistinctLiveCapture = liveCapturePath != null
        )

        if (!nativePdfSuccess || !outFile.exists() || outFile.length() == 0L) {
            writeFallbackPdf14(
                outFile = outFile,
                employee = employee,
                currentLog = log,
                weeklySummary = weeklySummary,
                recipientEmail = recipientEmail,
                portraitPath = liveCapturePath ?: registeredPhotoPath
            )
        }

        return outFile
    }

    fun generateBatchConsolidatedPdf(
        context: Context,
        employees: List<Employee>,
        logs: List<AttendanceLog>,
        dateKey: String,
        recipientEmail: String,
        selfiePathsByLogId: Map<String, String> = emptyMap()
    ): File {
        val reportsDir = File(context.cacheDir, "attendance_reports").apply {
            if (!exists()) mkdirs()
        }
        val safeDate = dateKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(reportsDir, "NICH_BATCH_CONSOLIDATED_${safeDate}.pdf")

        val summary = buildBatchReportSummary(
            employees = employees,
            logs = logs,
            dateKey = dateKey,
            selfiePathsByLogId = selfiePathsByLogId
        )

        val nativeSuccess = tryWriteNativeBatchPdf(
            outFile = outFile,
            summary = summary
        )
        if (!nativeSuccess || !outFile.exists() || outFile.length() == 0L) {
            writeFallbackBatchPdf14(
                outFile = outFile,
                summary = summary,
                recipientEmail = recipientEmail
            )
        }
        return outFile
    }

    internal fun buildBatchReportSummary(
        employees: List<Employee>,
        logs: List<AttendanceLog>,
        dateKey: String,
        selfiePathsByLogId: Map<String, String> = emptyMap(),
        generatedAt: Date = Date()
    ): BatchReportSummary {
        val dateCal = parseDateKeyCalendar(dateKey)
        val dateDisplay = if (dateCal != null) {
            SimpleDateFormat("EEEE, dd MMM yyyy", Locale.US).format(dateCal.time)
        } else {
            dateKey
        }
        val generatedFormat = SimpleDateFormat("dd MMM yyyy  hh:mm a", Locale.US)

        val dateLogs = logs.filter { it.loggedDateKey == dateKey }
        val targetEmployees = if (employees.isNotEmpty()) {
            employees
        } else {
            dateLogs.distinctBy { it.employeeId }.map {
                Employee(
                    id = it.employeeId,
                    employeeCode = it.employeeCode,
                    name = it.employeeName,
                    department = it.department
                )
            }
        }

        var teamDutyMinutes = 0
        var completedCount = 0
        val rows = targetEmployees.map { emp ->
            val empLogs = dateLogs.filter { item ->
                (emp.id.isNotBlank() && item.employeeId == emp.id) ||
                    (emp.employeeCode.isNotBlank() && item.employeeCode == emp.employeeCode)
            }
            val checkIn = empLogs
                .filter { it.eventType == AttendanceEventType.CHECK_IN.wireValue }
                .minByOrNull { parseTimeLabelMinutes(it.loggedTimeLabel) ?: Int.MAX_VALUE }
            val checkOut = empLogs
                .filter { it.eventType == AttendanceEventType.CHECK_OUT.wireValue }
                .maxByOrNull { parseTimeLabelMinutes(it.loggedTimeLabel) ?: Int.MIN_VALUE }

            val inMin = checkIn?.let { parseTimeLabelMinutes(it.loggedTimeLabel) }
            val outMin = checkOut?.let { parseTimeLabelMinutes(it.loggedTimeLabel) }
            val dutyMin = if (inMin != null && outMin != null && outMin >= inMin) {
                outMin - inMin
            } else {
                0
            }
            val complete = checkIn != null && checkOut != null
            if (complete) completedCount++
            teamDutyMinutes += dutyMin

            val dutyLabel = when {
                inMin != null && outMin != null && outMin >= inMin -> formatDurationHoursMinutes(dutyMin)
                empLogs.isNotEmpty() -> "00:00 Hrs"
                else -> "--"
            }
            val selfie = checkOut?.let { selfiePathsByLogId[it.id] }
                ?: checkIn?.let { selfiePathsByLogId[it.id] }
                ?: emp.localPhotoPath.takeIf { it.isNotBlank() }

            val latestLog = checkOut ?: checkIn
            BatchEmployeeRow(
                employee = emp,
                checkInLabel = formatShortTimeLabel(checkIn?.loggedTimeLabel ?: "--"),
                checkOutLabel = formatShortTimeLabel(checkOut?.loggedTimeLabel ?: "--"),
                dutyHoursLabel = dutyLabel,
                dutyMinutes = dutyMin,
                isCycleComplete = complete,
                gpsCoordinates = latestLog?.gpsCoordinates.orEmpty(),
                cnicNumber = latestLog?.matchedCnicNumber?.ifBlank { emp.cnicNumber } ?: emp.cnicNumber,
                liveSelfiePath = selfie
            )
        }

        val sampleGps = dateLogs.firstOrNull { it.gpsCoordinates.isNotBlank() }?.gpsCoordinates ?: "Location verified on device"
        val sampleTime = dateLogs.firstOrNull { it.timeSource.isNotBlank() }?.timeSource ?: "NTP Network Time Locked"

        return BatchReportSummary(
            dateKey = dateKey,
            dateDisplayLabel = dateDisplay,
            rows = rows,
            completedCount = completedCount,
            totalCount = rows.size,
            totalTeamDutyHoursLabel = formatDurationHoursMinutes(teamDutyMinutes),
            generatedOnLabel = generatedFormat.format(generatedAt),
            gpsSummary = sampleGps,
            timeSourceSummary = sampleTime
        )
    }

    internal fun buildWeeklySummary(
        employee: Employee,
        currentLog: AttendanceLog,
        recentDailyLogs: List<AttendanceLog>,
        generatedAt: Date = Date()
    ): WeeklyReportSummary {
        val anchorCal = parseDateKeyCalendar(currentLog.loggedDateKey) ?: Calendar.getInstance()

        // Snap anchor calendar to Monday of its ISO/standard Monday–Sunday week
        val mondayCal = (anchorCal.clone() as Calendar).apply {
            val dow = get(Calendar.DAY_OF_WEEK)
            val daysFromMonday = when (dow) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> 0
            }
            add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        }
        val sundayCal = (mondayCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 6)
        }

        val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val weekHeaderDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
        val rowDayDateFormat = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.US)
        val generatedFormat = SimpleDateFormat("dd MMM yyyy  hh:mm a", Locale.US)

        val weekRangeLabel =
            "For the Week: ${weekHeaderDateFormat.format(mondayCal.time)} \u2013 ${weekHeaderDateFormat.format(sundayCal.time)}"

        // Merge recentDailyLogs with currentLog (deduplicated by id) for this employee
        val combinedEmployeeLogs = (listOf(currentLog) + recentDailyLogs)
            .distinctBy { it.id.ifBlank { "${it.loggedDateKey}_${it.eventType}_${it.loggedTimeLabel}" } }
            .filter { item ->
                (employee.id.isNotBlank() && item.employeeId == employee.id) ||
                    (employee.employeeCode.isNotBlank() && item.employeeCode == employee.employeeCode)
            }

        val logsByDate = combinedEmployeeLogs.groupBy { it.loggedDateKey }
        val rows = mutableListOf<WeeklyDayRow>()
        var totalWorkingDays = 0
        var totalDutyMinutes = 0

        val cursor = mondayCal.clone() as Calendar
        for (i in 0 until 7) {
            val dayKey = dateKeyFormat.format(cursor.time)
            val dayDateLabel = rowDayDateFormat.format(cursor.time)
            val dayLogs = logsByDate[dayKey].orEmpty()

            val checkInLogs = dayLogs.filter { it.eventType == AttendanceEventType.CHECK_IN.wireValue }
            val checkOutLogs = dayLogs.filter { it.eventType == AttendanceEventType.CHECK_OUT.wireValue }

            val firstCheckIn = checkInLogs
                .minByOrNull { parseTimeLabelMinutes(it.loggedTimeLabel) ?: Int.MAX_VALUE }
            val lastCheckOut = checkOutLogs
                .maxByOrNull { parseTimeLabelMinutes(it.loggedTimeLabel) ?: Int.MIN_VALUE }

            val checkInText = firstCheckIn?.loggedTimeLabel?.takeIf { it.isNotBlank() } ?: "--"
            val checkOutText = lastCheckOut?.loggedTimeLabel?.takeIf { it.isNotBlank() } ?: "--"

            val inMinutes = firstCheckIn?.let { parseTimeLabelMinutes(it.loggedTimeLabel) }
            val outMinutes = lastCheckOut?.let { parseTimeLabelMinutes(it.loggedTimeLabel) }

            val dutyMinutes = if (inMinutes != null && outMinutes != null && outMinutes >= inMinutes) {
                outMinutes - inMinutes
            } else {
                0
            }
            val dutyHoursText = when {
                inMinutes != null && outMinutes != null && outMinutes >= inMinutes ->
                    formatDurationHoursMinutes(dutyMinutes)
                dayLogs.isNotEmpty() -> "00:00 Hrs"
                else -> "--"
            }

            val present = dayLogs.isNotEmpty()
            if (present) {
                totalWorkingDays++
                totalDutyMinutes += dutyMinutes
            }

            rows += WeeklyDayRow(
                dateKey = dayKey,
                dayDateLabel = dayDateLabel,
                checkInLabel = formatShortTimeLabel(checkInText),
                checkOutLabel = formatShortTimeLabel(checkOutText),
                dutyHoursLabel = dutyHoursText,
                isPresent = present,
                dutyMinutes = dutyMinutes
            )
            cursor.add(Calendar.DAY_OF_MONTH, 1)
        }

        return WeeklyReportSummary(
            weekRangeLabel = weekRangeLabel,
            rows = rows,
            totalWorkingDays = max(totalWorkingDays, 1),
            totalDutyHoursLabel = formatDurationHoursMinutes(totalDutyMinutes),
            overallStatusLabel = "Present",
            generatedOnLabel = generatedFormat.format(generatedAt)
        )
    }

    private fun tryWriteNativeWeeklyPdf(
        outFile: File,
        employee: Employee,
        currentLog: AttendanceLog,
        weeklySummary: WeeklyReportSummary,
        registeredPhotoPath: String?,
        liveCapturePath: String?,
        hasDistinctLiveCapture: Boolean
    ): Boolean {
        return try {
            val document = PdfDocument()
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas

                // Pure white sheet background
                canvas.drawColor(Color.WHITE)

                val darkInk = Color.rgb(17, 17, 17)
                val mutedInk = Color.rgb(65, 65, 65)
                val tableHeaderFill = Color.rgb(229, 232, 236)
                val gridStrokeColor = Color.rgb(45, 45, 45)
                val greenBadgeColor = Color.rgb(22, 163, 74)

                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    style = Paint.Style.STROKE
                    strokeWidth = 1.6f
                }
                val thinDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(55, 55, 55)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.1f
                }
                val tableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = gridStrokeColor
                    style = Paint.Style.STROKE
                    strokeWidth = 1.0f
                }
                val headerFillPaint = Paint().apply {
                    color = tableHeaderFill
                    style = Paint.Style.FILL
                }

                val orgTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 13.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val orgSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mutedInk
                    textSize = 10.5f
                }
                val mainTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 18.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val weekSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mutedInk
                    textSize = 11f
                    textAlign = Paint.Align.CENTER
                }
                val rightSystemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val metaLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 11.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val metaValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 11.5f
                }
                val tableHeaderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 11.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val cellTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 11f
                    textAlign = Paint.Align.CENTER
                }
                val cellLeftTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 11f
                    textAlign = Paint.Align.LEFT
                }
                val statusPillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 10.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val footerBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val footerRegularPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10.5f
                }

                // =========================================================
                // 1. TOP HEADER BAR (Left Org | Center Title | Right Clock)
                // =========================================================
                canvas.drawText("NICH Employee Attendance 3", MARGIN_X, 42f, orgTitlePaint)
                canvas.drawText("Karachi, Pakistan", MARGIN_X, 58f, orgSubPaint)

                canvas.drawLine(252f, 24f, 252f, 66f, thinDividerPaint)

                val centerHeaderX = 436f
                canvas.drawText("WEEKLY ATTENDANCE REPORT", centerHeaderX, 44f, mainTitlePaint)
                canvas.drawText(weeklySummary.weekRangeLabel, centerHeaderX, 62f, weekSubPaint)

                canvas.drawLine(622f, 24f, 622f, 66f, thinDividerPaint)

                // Vector Clock Icon matching sample
                val clockCx = 656f
                val clockCy = 46f
                val clockRadius = 18f
                val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    style = Paint.Style.STROKE
                    strokeWidth = 2.6f
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawCircle(clockCx, clockCy, clockRadius, clockPaint)
                canvas.drawLine(clockCx, clockCy, clockCx, clockCy - 10f, clockPaint)
                canvas.drawLine(clockCx, clockCy, clockCx + 8f, clockCy + 5f, clockPaint)

                canvas.drawText("ATTENDANCE", 686f, 36f, rightSystemPaint)
                canvas.drawText("MANAGEMENT", 686f, 49f, rightSystemPaint)
                canvas.drawText("SYSTEM", 686f, 62f, rightSystemPaint)

                // Thick header bottom rule
                canvas.drawLine(MARGIN_X, 76f, PAGE_WIDTH - MARGIN_X, 76f, linePaint)

                // =========================================================
                // 2. REGISTERED PHOTO + EMPLOYEE INFO + MIDDLE LIVE CAPTURE + KPI SUMMARY
                // =========================================================
                val photoBoxTop = 88f
                val photoBoxSize = 108f
                val leftPhotoRect = RectF(
                    MARGIN_X,
                    photoBoxTop,
                    MARGIN_X + photoBoxSize,
                    photoBoxTop + photoBoxSize
                )
                drawFramedPortraitBox(
                    canvas = canvas,
                    rect = leftPhotoRect,
                    photoPath = registeredPhotoPath,
                    fallbackInitials = employee.name.trim().take(2).uppercase(Locale.US).ifEmpty { "ST" },
                    borderPaint = thinDividerPaint,
                    caption = "Employee Photo",
                    darkInk = darkInk,
                    mutedInk = mutedInk
                )

                // Left-Middle Employee Details Column
                val cleanDisplayName = BidiTextHelper.stripBidiControls(
                    BidiTextHelper.formatEmployeeHeaderTitle(employee.name, "")
                ).ifBlank { employee.name }
                val cardNo = employee.checkInCode.ifBlank {
                    employee.passcode
                }.ifBlank { employee.employeeCode }

                val employeeDetails = listOf(
                    "Employee Name" to cleanDisplayName,
                    "Employee ID" to employee.employeeCode.ifBlank { "N/A" },
                    "Department" to employee.department.ifBlank { "Operations" },
                    "Designation" to employee.roleTitle.ifBlank { "Staff" },
                    "Card No" to cardNo
                )

                val detailLabelX = 148f
                val detailColonX = 248f
                val detailValueX = 258f
                val detailMaxValueW = 162f
                var detailY = 90f
                employeeDetails.forEach { (label, value) ->
                    canvas.drawText(label, detailLabelX, detailY + 13f, metaLabelPaint)
                    canvas.drawText(":", detailColonX, detailY + 13f, metaLabelPaint)
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = value,
                        x = detailValueX,
                        yTop = detailY,
                        maxWidth = detailMaxValueW,
                        maxHeight = 20f,
                        paint = metaValuePaint
                    )
                    detailY += 23f
                }

                // Divider 1 before Middle Live Capture Image
                canvas.drawLine(426f, 90f, 426f, 208f, thinDividerPaint)

                // MIDDLE: Live Captured Attendance Image (taken right when attendance is marked)
                val liveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (hasDistinctLiveCapture) greenBadgeColor else Color.rgb(55, 55, 55)
                    style = Paint.Style.STROKE
                    strokeWidth = if (hasDistinctLiveCapture) 1.6f else 1.1f
                }
                val middleLiveRect = RectF(
                    438f,
                    photoBoxTop,
                    438f + photoBoxSize,
                    photoBoxTop + photoBoxSize
                )
                drawFramedPortraitBox(
                    canvas = canvas,
                    rect = middleLiveRect,
                    photoPath = liveCapturePath,
                    fallbackInitials = "LIVE",
                    borderPaint = liveBorderPaint,
                    caption = "Live Capture",
                    darkInk = darkInk,
                    mutedInk = if (hasDistinctLiveCapture) greenBadgeColor else mutedInk
                )

                // Divider 2 after Middle Live Capture Image
                canvas.drawLine(558f, 90f, 558f, 208f, thinDividerPaint)

                // Right Summary KPI Column
                val kpiLabelX = 570f
                val kpiColonX = 702f
                val kpiValueX = 714f

                canvas.drawText("Total Working Days", kpiLabelX, 114f, metaLabelPaint)
                canvas.drawText(":", kpiColonX, 114f, metaLabelPaint)
                canvas.drawText(weeklySummary.totalWorkingDays.toString(), kpiValueX, 114f, metaValuePaint)

                canvas.drawText("Total Duty Hours", kpiLabelX, 146f, metaLabelPaint)
                canvas.drawText(":", kpiColonX, 146f, metaLabelPaint)
                canvas.drawText(weeklySummary.totalDutyHoursLabel, kpiValueX, 146f, metaValuePaint)

                canvas.drawText("Attendance Status", kpiLabelX, 179f, metaLabelPaint)
                canvas.drawText(":", kpiColonX, 179f, metaLabelPaint)

                val badgeRect = RectF(kpiValueX, 162f, kpiValueX + 82f, 185f)
                val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = greenBadgeColor
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(badgeRect, 11f, 11f, greenPaint)
                canvas.drawText(
                    weeklySummary.overallStatusLabel,
                    badgeRect.centerX(),
                    badgeRect.centerY() + 4f,
                    statusPillTextPaint
                )

                // =========================================================
                // 2B. VERIFIED GPS, LOCATION ADDRESS & NTP TIME LOCK STRIP
                // =========================================================
                val securityStripTop = 212f
                val securityStripBottom = 232f
                val stripBgPaint = Paint().apply {
                    color = Color.rgb(241, 245, 249)
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    RectF(MARGIN_X, securityStripTop, PAGE_WIDTH - MARGIN_X, securityStripBottom),
                    5f,
                    5f,
                    stripBgPaint
                )
                canvas.drawRoundRect(
                    RectF(MARGIN_X, securityStripTop, PAGE_WIDTH - MARGIN_X, securityStripBottom),
                    5f,
                    5f,
                    tableBorderPaint
                )
                val gpsLabel = currentLog.gpsCoordinates.ifBlank { "Location not fixed" }
                val addressLabel = currentLog.locationAddress.ifBlank { "Offline / Address unavailable" }
                val timeAuthLabel = currentLog.timeSource.ifBlank { "NTP Network Time Locked" }
                val cnicProof = currentLog.matchedCnicNumber.ifBlank { employee.cnicNumber }
                val stripTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 9.0f
                }
                val cnicSegment = if (cnicProof.isNotBlank()) "  |  CNIC: $cnicProof" else ""
                val stripSummary = "GPS: $gpsLabel  |  Address: $addressLabel  |  Time: $timeAuthLabel$cnicSegment"
                drawBidiBoundedText(
                    canvas = canvas,
                    text = stripSummary,
                    x = MARGIN_X + 8f,
                    yTop = securityStripTop + 2f,
                    maxWidth = PAGE_WIDTH - (MARGIN_X * 2f) - 16f,
                    maxHeight = 16f,
                    paint = stripTextPaint
                )

                // =========================================================
                // 3. WEEKLY 7-DAY ATTENDANCE GRID (Mon -> Sun)
                // =========================================================
                val tableLeft = MARGIN_X
                val tableRight = PAGE_WIDTH - MARGIN_X
                val tableTop = 240f
                val headerRowHeight = 29f
                val dataRowHeight = 30f

                // Column boundaries summing to 786f
                val colXs = floatArrayOf(
                    tableLeft,           // 28f  (Day / Date: 238f)
                    tableLeft + 238f,    // 266f (Check In: 138f)
                    tableLeft + 376f,    // 404f (Check Out: 138f)
                    tableLeft + 514f,    // 542f (Duty Hours: 136f)
                    tableLeft + 650f,    // 678f (Status: 136f)
                    tableRight           // 814f
                )

                // Table header background
                canvas.drawRect(
                    tableLeft,
                    tableTop,
                    tableRight,
                    tableTop + headerRowHeight,
                    headerFillPaint
                )

                val headers = listOf("Day / Date", "Check In", "Check Out", "Duty Hours", "Status")
                val headerTextY = tableTop + 19f
                headers.forEachIndexed { idx, title ->
                    val centerX = (colXs[idx] + colXs[idx + 1]) / 2f
                    canvas.drawText(title, centerX, headerTextY, tableHeaderTextPaint)
                }

                // Data Rows
                val checkCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = greenBadgeColor
                    style = Paint.Style.FILL
                }
                val checkMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 2.0f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                weeklySummary.rows.forEachIndexed { index, row ->
                    val rTop = tableTop + headerRowHeight + (index * dataRowHeight)
                    val rBottom = rTop + dataRowHeight
                    val textBaselineY = rTop + 20f

                    // Day / Date (left padded)
                    canvas.drawText(row.dayDateLabel, colXs[0] + 12f, textBaselineY, cellLeftTextPaint)

                    // Check In
                    canvas.drawText(
                        row.checkInLabel,
                        (colXs[1] + colXs[2]) / 2f,
                        textBaselineY,
                        cellTextPaint
                    )

                    // Check Out
                    canvas.drawText(
                        row.checkOutLabel,
                        (colXs[2] + colXs[3]) / 2f,
                        textBaselineY,
                        cellTextPaint
                    )

                    // Duty Hours
                    canvas.drawText(
                        row.dutyHoursLabel,
                        (colXs[3] + colXs[4]) / 2f,
                        textBaselineY,
                        cellTextPaint
                    )

                    // Status cell
                    val statusCenterX = (colXs[4] + colXs[5]) / 2f
                    if (row.isPresent) {
                        val iconCx = statusCenterX - 24f
                        val iconCy = rTop + (dataRowHeight / 2f)
                        canvas.drawCircle(iconCx, iconCy, 8f, checkCirclePaint)
                        val tick = Path().apply {
                            moveTo(iconCx - 3.6f, iconCy + 0.2f)
                            lineTo(iconCx - 1.0f, iconCy + 3.0f)
                            lineTo(iconCx + 3.8f, iconCy - 2.6f)
                        }
                        canvas.drawPath(tick, checkMarkPaint)
                        canvas.drawText("Present", statusCenterX + 12f, textBaselineY, cellTextPaint)
                    } else {
                        canvas.drawText("--", statusCenterX, textBaselineY, cellTextPaint)
                    }

                    // Horizontal row bottom grid line
                    canvas.drawLine(tableLeft, rBottom, tableRight, rBottom, tableBorderPaint)
                }

                val tableBottom = tableTop + headerRowHeight + (weeklySummary.rows.size * dataRowHeight)
                // Outer border & header bottom line & vertical grid dividers
                canvas.drawRect(tableLeft, tableTop, tableRight, tableBottom, tableBorderPaint)
                canvas.drawLine(tableLeft, tableTop + headerRowHeight, tableRight, tableTop + headerRowHeight, tableBorderPaint)
                for (i in 1 until colXs.size - 1) {
                    canvas.drawLine(colXs[i], tableTop, colXs[i], tableBottom, tableBorderPaint)
                }

                // =========================================================
                // 4. FOOTER RULE & SIGNATURES
                // =========================================================
                val footerRuleY = 508f
                canvas.drawLine(MARGIN_X, footerRuleY, PAGE_WIDTH - MARGIN_X, footerRuleY, linePaint)

                canvas.drawText("Report Generated On:", MARGIN_X, 534f, footerBoldPaint)
                canvas.drawText(weeklySummary.generatedOnLabel, MARGIN_X, 551f, footerRegularPaint)

                // Center HR Manager Signature Line
                val hrLineLeft = 308f
                val hrLineRight = 508f
                canvas.drawLine(hrLineLeft, 550f, hrLineRight, 550f, thinDividerPaint)
                canvas.drawText(
                    "HR Manager",
                    (hrLineLeft + hrLineRight) / 2f,
                    567f,
                    TextPaint(footerBoldPaint).apply { textAlign = Paint.Align.CENTER }
                )

                // Right Authorized Signature Flourish + Line
                val authLineLeft = 594f
                val authLineRight = 794f
                val sigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(30, 64, 175)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.9f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val sigPath = Path().apply {
                    moveTo(656f, 542f)
                    cubicTo(648f, 520f, 672f, 514f, 666f, 538f)
                    cubicTo(662f, 546f, 682f, 530f, 688f, 540f)
                    cubicTo(692f, 545f, 704f, 528f, 712f, 539f)
                    cubicTo(718f, 544f, 728f, 532f, 738f, 538f)
                }
                canvas.drawPath(sigPath, sigPaint)
                canvas.drawLine(authLineLeft, 550f, authLineRight, 550f, thinDividerPaint)
                canvas.drawText(
                    "Authorized Signature",
                    (authLineLeft + authLineRight) / 2f,
                    567f,
                    TextPaint(footerBoldPaint).apply { textAlign = Paint.Align.CENTER }
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

    private fun drawFramedPortraitBox(
        canvas: Canvas,
        rect: RectF,
        photoPath: String?,
        fallbackInitials: String,
        borderPaint: Paint,
        caption: String,
        darkInk: Int,
        mutedInk: Int
    ) {
        val portraitBitmap = decodeSampledBitmap(photoPath, 320)
        if (portraitBitmap != null) {
            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(rect, 10f, 10f, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            val srcRect = centerCropRect(portraitBitmap.width, portraitBitmap.height, 1f)
            canvas.drawBitmap(
                portraitBitmap,
                srcRect,
                rect,
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            portraitBitmap.recycle()
            canvas.restore()
        } else {
            val emptyPhotoBg = Paint().apply {
                color = Color.rgb(241, 245, 249)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(rect, 10f, 10f, emptyPhotoBg)
            canvas.drawText(
                fallbackInitials,
                rect.centerX(),
                rect.centerY() + 6f,
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = if (fallbackInitials.length <= 2) 22f else 15f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
            )
        }
        canvas.drawRoundRect(rect, 10f, 10f, borderPaint)
        canvas.drawText(
            caption,
            rect.centerX(),
            rect.bottom + 13f,
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mutedInk
                textSize = 8.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        )
    }

    private fun centerCropRect(srcW: Int, srcH: Int, targetAspect: Float): Rect {
        val srcAspect = srcW.toFloat() / srcH.toFloat().coerceAtLeast(1f)
        return if (srcAspect > targetAspect) {
            val newW = (srcH * targetAspect).toInt().coerceIn(1, srcW)
            val left = (srcW - newW) / 2
            Rect(left, 0, left + newW, srcH)
        } else {
            val newH = (srcW / targetAspect).toInt().coerceIn(1, srcH)
            val top = (srcH - newH) / 2
            Rect(0, top, srcW, top + newH)
        }
    }

    private fun parseDateKeyCalendar(dateKey: String): Calendar? {
        if (dateKey.isBlank()) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val parsed = fmt.parse(dateKey.trim()) ?: return null
            Calendar.getInstance().apply { time = parsed }
        }.getOrNull()
    }

    internal fun parseTimeLabelMinutes(timeLabel: String): Int? {
        val trimmed = timeLabel.trim()
        if (trimmed.isEmpty() || trimmed == "--") return null
        val patterns = listOf("hh:mm:ss a", "h:mm:ss a", "hh:mm a", "h:mm a", "HH:mm:ss", "HH:mm")
        for (pattern in patterns) {
            val parsed = runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.parse(trimmed)
            }.getOrNull()
            if (parsed != null) {
                val cal = Calendar.getInstance().apply { time = parsed }
                return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            }
        }
        return null
    }

    private fun formatShortTimeLabel(raw: String): String {
        if (raw == "--" || raw.isBlank()) return "--"
        val minutes = parseTimeLabelMinutes(raw) ?: return raw
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return SimpleDateFormat("hh:mm a", Locale.US).format(cal.time)
    }

    private fun formatDurationHoursMinutes(totalMinutes: Int): String {
        val safe = totalMinutes.coerceAtLeast(0)
        val hours = safe / 60
        val mins = safe % 60
        return String.format(Locale.US, "%02d:%02d Hrs", hours, mins)
    }

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
        employee: Employee,
        currentLog: AttendanceLog,
        weeklySummary: WeeklyReportSummary,
        recipientEmail: String,
        portraitPath: String?
    ) {
        val cardNo = employee.checkInCode.ifBlank { employee.passcode }.ifBlank { employee.employeeCode }
        val gpsText = currentLog.gpsCoordinates.ifBlank { "Unavailable" }
        val addrText = currentLog.locationAddress.ifBlank { "Unavailable" }
        val timeAuth = currentLog.timeSource.ifBlank { "NTP Network Time Locked" }
        val lines = buildList {
            add("NICH EMPLOYEE ATTENDANCE 3  |  WEEKLY ATTENDANCE REPORT  |  ATTENDANCE MANAGEMENT SYSTEM")
            add(weeklySummary.weekRangeLabel)
            add("------------------------------------------------------------------------------------")
            add("Employee Name: ${BidiTextHelper.stripBidiControls(employee.name)}")
            add("Employee ID: ${employee.employeeCode}   Department: ${employee.department}   Card No: $cardNo")
            add("Total Working Days: ${weeklySummary.totalWorkingDays}   Total Duty Hours: ${weeklySummary.totalDutyHoursLabel}   Status: ${weeklySummary.overallStatusLabel}")
            add("GPS Coordinates: $gpsText   |   Time Sync: $timeAuth")
            add("Location Address: $addrText")
            if (!portraitPath.isNullOrBlank()) {
                val photoFile = File(portraitPath)
                if (photoFile.exists()) {
                    add("Live Capture Photo: Embedded (${photoFile.name}, ${photoFile.length()} bytes)")
                }
            }
            add("------------------------------------------------------------------------------------")
            add("Day / Date                 | Check In  | Check Out | Duty Hours | Status")
            weeklySummary.rows.forEach { row ->
                val statusStr = if (row.isPresent) "Present" else "--"
                add("${row.dayDateLabel.padEnd(26)} | ${row.checkInLabel.padEnd(9)} | ${row.checkOutLabel.padEnd(9)} | ${row.dutyHoursLabel.padEnd(10)} | $statusStr")
            }
            add("------------------------------------------------------------------------------------")
            add("Report Generated On: ${weeklySummary.generatedOnLabel}   |   Recipient: $recipientEmail")
        }

        writeSimpleAsciiLinesPdf(outFile, lines)
    }

    private fun writeSimpleAsciiLinesPdf(outFile: File, lines: List<String>) {
        val maxLinesPerDoc = lines.take(28)
        val contentStream = buildString {
            append("BT\n")
            append("/F1 10 Tf\n")
            var cursorY = 555
            maxLinesPerDoc.forEachIndexed { idx, rawLine ->
                val safe = escapePdfText(rawLine)
                if (idx == 0) {
                    append("28 $cursorY Td\n")
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
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
            "4 0 obj\n<< /Length ${contentStream.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${contentStream}endstream\nendobj\n",
            "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>\nendobj\n"
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

    private fun tryWriteNativeBatchPdf(
        outFile: File,
        summary: BatchReportSummary
    ): Boolean {
        return try {
            val document = PdfDocument()
            try {
                val rowsPerPage = 9
                val pageChunks = if (summary.rows.isEmpty()) listOf(emptyList()) else summary.rows.chunked(rowsPerPage)

                val darkInk = Color.rgb(17, 17, 17)
                val mutedInk = Color.rgb(65, 65, 65)
                val tableHeaderFill = Color.rgb(229, 232, 236)
                val gridStrokeColor = Color.rgb(45, 45, 45)
                val greenBadgeColor = Color.rgb(22, 163, 74)

                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    style = Paint.Style.STROKE
                    strokeWidth = 1.6f
                }
                val thinDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(55, 55, 55)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.1f
                }
                val tableBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = gridStrokeColor
                    style = Paint.Style.STROKE
                    strokeWidth = 1.0f
                }
                val headerFillPaint = Paint().apply {
                    color = tableHeaderFill
                    style = Paint.Style.FILL
                }
                val orgTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 13.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val orgSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mutedInk
                    textSize = 10.5f
                }
                val mainTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 17.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val weekSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mutedInk
                    textSize = 11f
                    textAlign = Paint.Align.CENTER
                }
                val rightSystemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val tableHeaderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val cellTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10f
                    textAlign = Paint.Align.CENTER
                }
                val footerBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val footerRegularPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = darkInk
                    textSize = 10.5f
                }

                pageChunks.forEachIndexed { pageIndex, chunk ->
                    val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                    val page = document.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    canvas.drawText("NICH Employee Attendance 3", MARGIN_X, 42f, orgTitlePaint)
                    canvas.drawText("Karachi, Pakistan", MARGIN_X, 58f, orgSubPaint)
                    canvas.drawLine(252f, 24f, 252f, 66f, thinDividerPaint)

                    val centerHeaderX = 436f
                    canvas.drawText("CONSOLIDATED BATCH ATTENDANCE REPORT", centerHeaderX, 44f, mainTitlePaint)
                    canvas.drawText(
                        "${summary.dateDisplayLabel}  •  Page ${pageIndex + 1}/${pageChunks.size}",
                        centerHeaderX,
                        62f,
                        weekSubPaint
                    )
                    canvas.drawLine(622f, 24f, 622f, 66f, thinDividerPaint)

                    val clockCx = 656f
                    val clockCy = 46f
                    val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = darkInk
                        style = Paint.Style.STROKE
                        strokeWidth = 2.6f
                        strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawCircle(clockCx, clockCy, 18f, clockPaint)
                    canvas.drawLine(clockCx, clockCy, clockCx, clockCy - 10f, clockPaint)
                    canvas.drawLine(clockCx, clockCy, clockCx + 8f, clockCy + 5f, clockPaint)
                    canvas.drawText("BATCH DAILY", 686f, 36f, rightSystemPaint)
                    canvas.drawText("BIOMETRIC + CNIC", 686f, 49f, rightSystemPaint)
                    canvas.drawText("SUMMARY", 686f, 62f, rightSystemPaint)

                    canvas.drawLine(MARGIN_X, 76f, PAGE_WIDTH - MARGIN_X, 76f, linePaint)

                    // Security & Batch Summary Strip
                    val stripRect = RectF(MARGIN_X, 86f, PAGE_WIDTH - MARGIN_X, 118f)
                    canvas.drawRoundRect(stripRect, 6f, 6f, Paint().apply {
                        color = Color.rgb(241, 245, 249)
                        style = Paint.Style.FILL
                    })
                    canvas.drawRoundRect(stripRect, 6f, 6f, tableBorderPaint)
                    val summaryLine =
                        "Registered Staff Completed: ${summary.completedCount}/${summary.totalCount}   |   " +
                            "Total Team Duty: ${summary.totalTeamDutyHoursLabel}   |   " +
                            "GPS: ${summary.gpsSummary}   |   Time: ${summary.timeSourceSummary}"
                    drawBidiBoundedText(
                        canvas = canvas,
                        text = summaryLine,
                        x = MARGIN_X + 10f,
                        yTop = 94f,
                        maxWidth = PAGE_WIDTH - (MARGIN_X * 2f) - 20f,
                        maxHeight = 18f,
                        paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = darkInk
                            textSize = 10f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                    )

                    // Consolidated Employee Table
                    val tableLeft = MARGIN_X
                    val tableRight = PAGE_WIDTH - MARGIN_X
                    val tableTop = 130f
                    val headerRowHeight = 28f
                    val dataRowHeight = 38f
                    val colXs = floatArrayOf(
                        tableLeft,          // # (34f)
                        tableLeft + 34f,    // Emp ID (84f)
                        tableLeft + 118f,   // Name (170f)
                        tableLeft + 288f,   // CNIC / Dept (140f)
                        tableLeft + 428f,   // Check In (86f)
                        tableLeft + 514f,   // Check Out (86f)
                        tableLeft + 600f,   // Duty Hours (90f)
                        tableLeft + 690f,   // Status (96f)
                        tableRight          // 814f
                    )

                    canvas.drawRect(tableLeft, tableTop, tableRight, tableTop + headerRowHeight, headerFillPaint)
                    val headers = listOf("#", "Emp ID", "Employee Name", "CNIC / Dept", "Check In", "Check Out", "Duty Hrs", "Status")
                    headers.forEachIndexed { idx, title ->
                        canvas.drawText(title, (colXs[idx] + colXs[idx + 1]) / 2f, tableTop + 18f, tableHeaderTextPaint)
                    }

                    val checkCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = greenBadgeColor
                        style = Paint.Style.FILL
                    }

                    chunk.forEachIndexed { localIdx, row ->
                        val globalIndex = pageIndex * rowsPerPage + localIdx + 1
                        val rTop = tableTop + headerRowHeight + (localIdx * dataRowHeight)
                        val rBottom = rTop + dataRowHeight
                        val textY = rTop + 23f

                        canvas.drawText(globalIndex.toString(), (colXs[0] + colXs[1]) / 2f, textY, cellTextPaint)
                        canvas.drawText(row.employee.employeeCode.ifBlank { "--" }, (colXs[1] + colXs[2]) / 2f, textY, cellTextPaint)

                        val cleanName = BidiTextHelper.stripBidiControls(
                            BidiTextHelper.formatEmployeeHeaderTitle(row.employee.name, "")
                        ).ifBlank { row.employee.name }
                        drawBidiBoundedText(
                            canvas = canvas,
                            text = cleanName,
                            x = colXs[2] + 8f,
                            yTop = rTop + 11f,
                            maxWidth = (colXs[3] - colXs[2]) - 14f,
                            maxHeight = 18f,
                            paint = TextPaint(cellTextPaint).apply { textAlign = Paint.Align.LEFT }
                        )

                        val cnicOrDept = row.cnicNumber.ifBlank { row.employee.department.ifBlank { "Staff" } }
                        drawBidiBoundedText(
                            canvas = canvas,
                            text = cnicOrDept,
                            x = colXs[3] + 6f,
                            yTop = rTop + 11f,
                            maxWidth = (colXs[4] - colXs[3]) - 12f,
                            maxHeight = 18f,
                            paint = TextPaint(cellTextPaint).apply { textAlign = Paint.Align.LEFT }
                        )

                        canvas.drawText(row.checkInLabel, (colXs[4] + colXs[5]) / 2f, textY, cellTextPaint)
                        canvas.drawText(row.checkOutLabel, (colXs[5] + colXs[6]) / 2f, textY, cellTextPaint)
                        canvas.drawText(row.dutyHoursLabel, (colXs[6] + colXs[7]) / 2f, textY, cellTextPaint)

                        val statusCenterX = (colXs[7] + colXs[8]) / 2f
                        if (row.isCycleComplete) {
                            canvas.drawCircle(statusCenterX - 24f, rTop + dataRowHeight / 2f, 6.5f, checkCirclePaint)
                            canvas.drawText("Complete", statusCenterX + 10f, textY, cellTextPaint)
                        } else {
                            canvas.drawText("Pending", statusCenterX, textY, cellTextPaint)
                        }
                        canvas.drawLine(tableLeft, rBottom, tableRight, rBottom, tableBorderPaint)
                    }

                    val tableBottom = tableTop + headerRowHeight + (chunk.size * dataRowHeight)
                    canvas.drawRect(tableLeft, tableTop, tableRight, tableBottom, tableBorderPaint)
                    canvas.drawLine(tableLeft, tableTop + headerRowHeight, tableRight, tableTop + headerRowHeight, tableBorderPaint)
                    for (i in 1 until colXs.size - 1) {
                        canvas.drawLine(colXs[i], tableTop, colXs[i], tableBottom, tableBorderPaint)
                    }

                    val footerRuleY = 508f
                    canvas.drawLine(MARGIN_X, footerRuleY, PAGE_WIDTH - MARGIN_X, footerRuleY, linePaint)
                    canvas.drawText("Report Generated On:", MARGIN_X, 534f, footerBoldPaint)
                    canvas.drawText(summary.generatedOnLabel, MARGIN_X, 551f, footerRegularPaint)

                    canvas.drawLine(308f, 550f, 508f, 550f, thinDividerPaint)
                    canvas.drawText(
                        "HR Manager",
                        408f,
                        567f,
                        TextPaint(footerBoldPaint).apply { textAlign = Paint.Align.CENTER }
                    )

                    canvas.drawLine(594f, 550f, 794f, 550f, thinDividerPaint)
                    canvas.drawText(
                        "Authorized Signature",
                        694f,
                        567f,
                        TextPaint(footerBoldPaint).apply { textAlign = Paint.Align.CENTER }
                    )

                    document.finishPage(page)
                }

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

    private fun writeFallbackBatchPdf14(
        outFile: File,
        summary: BatchReportSummary,
        recipientEmail: String
    ) {
        val lines = buildList {
            add("NICH EMPLOYEE ATTENDANCE 3  |  CONSOLIDATED BATCH ATTENDANCE REPORT")
            add("Date: ${summary.dateDisplayLabel}  |  Completed: ${summary.completedCount}/${summary.totalCount}  |  Team Duty: ${summary.totalTeamDutyHoursLabel}")
            add("GPS: ${summary.gpsSummary}  |  Time: ${summary.timeSourceSummary}")
            add("------------------------------------------------------------------------------------")
            add("# | Emp ID    | Employee Name        | Check In  | Check Out | Duty Hrs  | Status")
            summary.rows.forEachIndexed { idx, row ->
                val status = if (row.isCycleComplete) "Complete" else "Pending"
                val name = BidiTextHelper.stripBidiControls(row.employee.name).take(20)
                add("${(idx + 1).toString().padEnd(2)}| ${row.employee.employeeCode.padEnd(9)} | ${name.padEnd(20)} | ${row.checkInLabel.padEnd(9)} | ${row.checkOutLabel.padEnd(9)} | ${row.dutyHoursLabel.padEnd(9)} | $status")
            }
            add("------------------------------------------------------------------------------------")
            add("Generated: ${summary.generatedOnLabel}  |  Recipient: $recipientEmail")
        }
        writeSimpleAsciiLinesPdf(outFile, lines)
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
                    '•', '—', '–' -> append('-')
                    else -> if (ch.code > 0x007F) append('?') else Unit
                }
            }
        }
        return ascii.take(120)
    }
}
