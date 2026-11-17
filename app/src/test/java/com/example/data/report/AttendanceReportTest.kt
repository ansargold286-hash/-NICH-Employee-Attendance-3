package com.example.data.report

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttendanceReportTest {

    @Test
    fun generateReportPdf_createsValidPdfFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val employee = Employee(
            id = "emp_muhammad_ansar",
            employeeCode = "NICH-0147",
            name = "محمد انصار (Muhammad Ansar)",
            department = "Executive Operations",
            roleTitle = "Authorized Staff",
            passcode = "147",
            checkInCode = "147",
            checkOutCode = "369"
        )
        val log = AttendanceLog(
            id = "log_pdf_test_001",
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            department = employee.department,
            eventType = AttendanceEventType.CHECK_IN.wireValue,
            verificationMethod = VerificationMethod.PASSCODE.wireValue,
            confidenceScore = 100L,
            loggedTimeLabel = "09:10:00 AM",
            loggedDateKey = "2026-11-15"
        )

        val selfieFile = java.io.File(context.cacheDir, "unit_test_selfie.jpg")
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(selfieFile).use { stream ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        }
        bmp.recycle()

        val pdfFile = AttendancePdfGenerator.generateReportPdf(
            context = context,
            employee = employee,
            log = log,
            eventType = AttendanceEventType.CHECK_IN,
            verificationMethod = VerificationMethod.PASSCODE,
            confidenceScore = 100,
            syncedToCloud = false,
            recentDailyLogs = listOf(log),
            recipientEmail = AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL,
            liveSelfiePath = selfieFile.absolutePath
        )

        try {
            assertTrue(pdfFile.exists())
            assertTrue(pdfFile.length() > 200L)
            val rawBytes = pdfFile.readBytes()
            val header = rawBytes.copyOfRange(0, 5).toString(StandardCharsets.US_ASCII)
            assertEquals("%PDF-", header)
            val pdfContentText = rawBytes.toString(StandardCharsets.US_ASCII)
            assertTrue(pdfContentText.contains("unit_test_selfie.jpg") || rawBytes.size > 1500)
        } finally {
            pdfFile.delete()
            selfieFile.delete()
        }
    }

    @Test
    fun buildWeeklySummary_aggregatesMondayToSundayDutyHoursAccurately() {
        val employee = Employee(
            id = "emp_ali",
            employeeCode = "EMP-001",
            name = "Ali Raza",
            department = "Operations",
            roleTitle = "Staff",
            checkInCode = "1001"
        )
        val monIn = AttendanceLog(
            id = "mon_in",
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            eventType = AttendanceEventType.CHECK_IN.wireValue,
            loggedTimeLabel = "09:00:00 AM",
            loggedDateKey = "2025-11-10"
        )
        val monOut = AttendanceLog(
            id = "mon_out",
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            eventType = AttendanceEventType.CHECK_OUT.wireValue,
            loggedTimeLabel = "05:00:00 PM",
            loggedDateKey = "2025-11-10"
        )
        val sunOut = AttendanceLog(
            id = "sun_out",
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            eventType = AttendanceEventType.CHECK_OUT.wireValue,
            loggedTimeLabel = "05:10:00 PM",
            loggedDateKey = "2025-11-16"
        )
        val sunIn = AttendanceLog(
            id = "sun_in",
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            eventType = AttendanceEventType.CHECK_IN.wireValue,
            loggedTimeLabel = "09:10:00 AM",
            loggedDateKey = "2025-11-16"
        )

        val summary = AttendancePdfGenerator.buildWeeklySummary(
            employee = employee,
            currentLog = sunOut,
            recentDailyLogs = listOf(monIn, monOut, sunIn, sunOut)
        )

        assertEquals(7, summary.rows.size)
        assertEquals("Monday, 10 Nov 2025", summary.rows.first().dayDateLabel)
        assertEquals("Sunday, 16 Nov 2025", summary.rows.last().dayDateLabel)
        assertEquals("09:00 AM", summary.rows.first().checkInLabel)
        assertEquals("05:00 PM", summary.rows.first().checkOutLabel)
        assertEquals("08:00 Hrs", summary.rows.first().dutyHoursLabel)
        assertEquals(true, summary.rows.first().isPresent)
        assertEquals(false, summary.rows[1].isPresent)
        assertEquals(2, summary.totalWorkingDays)
        assertEquals("16:00 Hrs", summary.totalDutyHoursLabel)
        assertEquals("Present", summary.overallStatusLabel)
        assertTrue(summary.weekRangeLabel.contains("10 Nov 2025"))
        assertTrue(summary.weekRangeLabel.contains("16 Nov 2025"))
    }

    @Test
    fun smtpEmailSender_executesCompleteMimeAttachmentHandshake() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val employee = Employee(
            id = "emp_ansar",
            employeeCode = "NICH-0147",
            name = "محمد انصار (Muhammad Ansar)",
            department = "Executive Operations",
            roleTitle = "Authorized Staff"
        )
        val log = AttendanceLog(
            id = "log_smtp_test_001",
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            department = employee.department,
            eventType = AttendanceEventType.CHECK_OUT.wireValue,
            verificationMethod = VerificationMethod.FACE_SCAN_1.wireValue,
            confidenceScore = 97L,
            loggedTimeLabel = "05:30:00 PM",
            loggedDateKey = "2026-11-15"
        )
        val pdfFile = AttendancePdfGenerator.generateReportPdf(
            context = context,
            employee = employee,
            log = log,
            eventType = AttendanceEventType.CHECK_OUT,
            verificationMethod = VerificationMethod.FACE_SCAN_1,
            confidenceScore = 97,
            syncedToCloud = true,
            recentDailyLogs = listOf(log),
            recipientEmail = AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL
        )

        val selfieFile = java.io.File(context.cacheDir, "smtp_unit_selfie.jpg")
        val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(selfieFile).use { stream ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        }
        bmp.recycle()

        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val receivedCommands = mutableListOf<String>()
        val dataPayload = StringBuilder()
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            serverSocket.use { srv ->
                srv.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)

                    fun reply(msg: String) {
                        writer.write("$msg\r\n")
                        writer.flush()
                    }

                    reply("220 localhost ESMTP ready")
                    var inData = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (inData) {
                            if (line == ".") {
                                inData = false
                                reply("250 2.0.0 OK queued")
                            } else {
                                dataPayload.append(line).append('\n')
                            }
                        } else {
                            receivedCommands += line
                            when {
                                line.startsWith("EHLO") -> reply("250-localhost\r\n250 AUTH LOGIN")
                                line == "AUTH LOGIN" -> reply("334 VXNlcm5hbWU6")
                                receivedCommands.count { it == "AUTH LOGIN" } == 1 &&
                                    receivedCommands.size >= 3 &&
                                    receivedCommands[receivedCommands.size - 2] == "AUTH LOGIN" -> reply("334 UGFzc3dvcmQ6")
                                line.startsWith("MAIL FROM:") -> reply("250 2.1.0 OK")
                                line.startsWith("RCPT TO:") -> reply("250 2.1.5 OK")
                                line == "DATA" -> {
                                    reply("354 End data with <CR><LF>.<CR><LF>")
                                    inData = true
                                }
                                line == "QUIT" -> {
                                    reply("221 Bye")
                                    break
                                }
                                else -> reply("235 2.7.0 Authentication successful")
                            }
                        }
                    }
                }
            }
            serverDone.countDown()
        }
        serverThread.start()

        try {
            val sender = SmtpEmailSender { cfg ->
                Socket(cfg.host, cfg.port)
            }
            sender.sendWithAttachment(
                config = SmtpConfig(
                    host = "127.0.0.1",
                    port = serverSocket.localPort,
                    useSsl = false,
                    username = "Nichemployeeattendance@gmail.com",
                    appPassword = "abcd efgh ijkl mnop"
                ),
                toEmail = AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL,
                subject = "NICH Attendance 3 • CHECK-OUT • محمد انصار",
                plainTextBody = "Attendance verified for NICH-0147.",
                attachmentFile = pdfFile,
                extraAttachmentFile = selfieFile
            )

            assertTrue(serverDone.await(5, TimeUnit.SECONDS))
            assertTrue(receivedCommands.any { it == "MAIL FROM:<Nichemployeeattendance@gmail.com>" })
            assertTrue(receivedCommands.any { it == "RCPT TO:<Nichemployeeattendance@gmail.com>" })
            val rawMime = dataPayload.toString()
            assertTrue(rawMime.contains("Content-Type: application/pdf; name=\"${pdfFile.name}\""))
            assertTrue(rawMime.contains("Content-Type: image/jpeg; name=\"${selfieFile.name}\""))
            assertTrue(rawMime.contains("Content-Transfer-Encoding: base64"))
        } finally {
            pdfFile.delete()
            selfieFile.delete()
            runCatching { serverSocket.close() }
        }
    }
}
