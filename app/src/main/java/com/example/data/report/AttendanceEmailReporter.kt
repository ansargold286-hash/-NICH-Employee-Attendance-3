package com.example.data.report

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class EmailReportStatus(val label: String) {
    DISPATCHING("Generating & Sending PDF..."),
    SENT("Sent PDF to Nichemployeeattendance@gmail.com"),
    QUEUED_OFFLINE("PDF Ready • Sends via Device Gmail"),
    NEEDS_CONFIG("PDF Ready • Tap Send Email & Finish")
}

object DeviceAccountResolver {
    fun resolveLoggedInGoogleEmail(context: Context): String? {
        return runCatching {
            val manager = AccountManager.get(context)
            val googleAccounts = manager.getAccountsByType("com.google")
            val primaryGoogle = googleAccounts
                .map { it.name.trim() }
                .firstOrNull { it.contains("@") }
            if (!primaryGoogle.isNullOrBlank()) {
                return@runCatching primaryGoogle
            }
            manager.accounts
                .map { it.name.trim() }
                .firstOrNull { it.contains("@") }
        }.getOrNull()
    }
}

class AttendanceEmailReporter(
    private val context: Context,
    private val smtpSender: SmtpEmailSender = SmtpEmailSender(),
    private val senderEmailProvider: () -> String = { BuildConfig.SMTP_EMAIL },
    private val appPasswordProvider: () -> String = { BuildConfig.SMTP_APP_PASSWORD }
) {
    fun hasConfiguredSmtpCredentials(): Boolean {
        val appPassword = appPasswordProvider().trim()
        return appPassword.isNotEmpty() && appPassword != PLACEHOLDER_PASSWORD
    }

    fun resolveSenderAccountEmail(): String {
        return DeviceAccountResolver.resolveLoggedInGoogleEmail(context)
            ?: senderEmailProvider().trim().ifEmpty { TARGET_RECIPIENT_EMAIL }
    }

    fun resolveRecipientEmail(): String = TARGET_RECIPIENT_EMAIL

    suspend fun generateReportPdfFile(
        employee: Employee,
        log: AttendanceLog,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        syncedToCloud: Boolean,
        recentDailyLogs: List<AttendanceLog>,
        liveSelfiePath: String? = null
    ): File = withContext(Dispatchers.IO) {
        val recipient = resolveRecipientEmail()
        val pdfFile = AttendancePdfGenerator.generateReportPdf(
            context = context,
            employee = employee,
            log = log,
            eventType = eventType,
            verificationMethod = verificationMethod,
            confidenceScore = confidenceScore,
            syncedToCloud = syncedToCloud,
            recentDailyLogs = recentDailyLogs,
            recipientEmail = recipient,
            liveSelfiePath = liveSelfiePath
        )
        pruneOldCachedReports(pdfFile)
        pdfFile
    }

    suspend fun generateAndSendReport(
        employee: Employee,
        log: AttendanceLog,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        syncedToCloud: Boolean,
        recentDailyLogs: List<AttendanceLog>,
        liveSelfiePath: String? = null
    ): EmailReportStatus = withContext(Dispatchers.IO) {
        val pdfFile = try {
            generateReportPdfFile(
                employee = employee,
                log = log,
                eventType = eventType,
                verificationMethod = verificationMethod,
                confidenceScore = confidenceScore,
                syncedToCloud = syncedToCloud,
                recentDailyLogs = recentDailyLogs,
                liveSelfiePath = liveSelfiePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate attendance PDF report", e)
            return@withContext EmailReportStatus.QUEUED_OFFLINE
        }

        val senderEmail = resolveSenderAccountEmail()
        val recipientEmail = resolveRecipientEmail()
        val appPassword = appPasswordProvider().trim()
        if (appPassword.isEmpty() || appPassword == PLACEHOLDER_PASSWORD) {
            Log.i(TAG, "PDF generated for device Gmail dispatch (${pdfFile.name}, sender=$senderEmail)")
            return@withContext EmailReportStatus.NEEDS_CONFIG
        }

        val subject = buildSubject(eventType, employee)
        val body = buildPlainTextBody(
            employee = employee,
            log = log,
            eventType = eventType,
            verificationMethod = verificationMethod,
            confidenceScore = confidenceScore,
            syncedToCloud = syncedToCloud,
            deviceAccountEmail = senderEmail
        )

        val livePhotoFile = liveSelfiePath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }

        return@withContext try {
            smtpSender.sendWithAttachment(
                config = SmtpConfig(
                    username = senderEmail,
                    appPassword = appPassword
                ),
                toEmail = recipientEmail,
                subject = subject,
                plainTextBody = body,
                attachmentFile = pdfFile,
                extraAttachmentFile = livePhotoFile
            )
            EmailReportStatus.SENT
        } catch (e: Exception) {
            Log.w(TAG, "Background SMTP email deferred; PDF preserved for device Gmail: ${e.message}")
            EmailReportStatus.QUEUED_OFFLINE
        }
    }

    fun buildDeviceGmailShareIntent(
        pdfFile: File,
        employee: Employee,
        log: AttendanceLog,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        syncedToCloud: Boolean
    ): Intent {
        val recipientEmail = resolveRecipientEmail()
        val deviceSenderEmail = DeviceAccountResolver.resolveLoggedInGoogleEmail(context)
        val pdfUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )

        val subject = buildSubject(eventType, employee)
        val body = buildPlainTextBody(
            employee = employee,
            log = log,
            eventType = eventType,
            verificationMethod = verificationMethod,
            confidenceScore = confidenceScore,
            syncedToCloud = syncedToCloud,
            deviceAccountEmail = deviceSenderEmail
        )

        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val gmailTargeted = Intent(baseIntent).setPackage(GMAIL_PACKAGE_NAME)
        val canLaunchGmailDirectly = runCatching {
            context.packageManager.resolveActivity(gmailTargeted, 0) != null
        }.getOrDefault(false)

        return if (canLaunchGmailDirectly) {
            gmailTargeted
        } else {
            Intent.createChooser(baseIntent, "Send Attendance PDF Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun buildSubject(eventType: AttendanceEventType, employee: Employee): String {
        return "NICH Employee Attendance 3 • ${eventType.displayLabel} • ${employee.name} (${employee.employeeCode})"
    }

    private fun buildPlainTextBody(
        employee: Employee,
        log: AttendanceLog,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        syncedToCloud: Boolean,
        deviceAccountEmail: String?
    ): String {
        return buildString {
            appendLine("NICH Employee Attendance 3 — Automated Attendance Report")
            appendLine("==========================================================")
            appendLine("Employee Name: ${employee.name}")
            appendLine("Employee ID: ${employee.employeeCode}")
            appendLine("Department: ${employee.department}")
            appendLine("Role: ${employee.roleTitle}")
            appendLine("Event Type: ${eventType.displayLabel} (${eventType.wireValue})")
            appendLine("Verification Method: ${verificationMethod.displayLabel}")
            appendLine("Match Confidence: ${confidenceScore.coerceIn(0, 100)}%")
            appendLine("Timestamp: ${log.loggedTimeLabel} (${log.loggedDateKey})")
            appendLine("Time Authority: ${log.timeSource.ifBlank { "Network Time (NTP)" }}")
            appendLine("GPS Coordinates: ${log.gpsCoordinates.ifBlank { "Unavailable" }}")
            appendLine("Location Address: ${log.locationAddress.ifBlank { "Unavailable" }}")
            appendLine("Cloud Sync: ${if (syncedToCloud) "Cloud Firestore Live" else "Saved Offline (Queued)"}")
            if (!deviceAccountEmail.isNullOrBlank()) {
                appendLine("Dispatched From Device Account: $deviceAccountEmail")
            }
            appendLine()
            appendLine("The structured PDF attendance report (with live capture photo, GPS address, and weekly summary) is attached.")
        }
    }

    private fun pruneOldCachedReports(latestFile: File) {
        runCatching {
            val dir = latestFile.parentFile ?: return
            dir.listFiles()
                ?.filter { it.absolutePath != latestFile.absolutePath && it.extension.equals("pdf", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(4)
                ?.forEach { it.delete() }
        }
    }

    companion object {
        private const val TAG = "AttendanceEmail"
        private const val GMAIL_PACKAGE_NAME = "com.google.android.gm"
        const val TARGET_RECIPIENT_EMAIL = "Nichemployeeattendance@gmail.com"
        private const val PLACEHOLDER_PASSWORD = "MY_GMAIL_APP_PASSWORD"
    }
}
