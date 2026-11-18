package com.example.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalRegistrationInput
import com.example.data.local.OfflineAttendanceStore
import com.example.data.local.PendingServerEmailReport
import com.example.data.model.AttendanceEventType
import com.example.data.model.VerificationMethod
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerSyncClientTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nich_offline_attendance", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun flushPending_authenticatesWithApikeyHeaderWithoutBearerForSbTokens_andRetriesWithoutDataLoss() = runTest {
        data class RecordedRequest(
            val path: String,
            val headers: Map<String, String>,
            val body: String
        )

        val acceptRequests = AtomicBoolean(false)
        val recordedRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

        val serverThread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    val input = BufferedInputStream(socket.getInputStream())
                    val requestLine = readHttpLine(input) ?: run {
                        socket.close()
                        continue
                    }
                    val path = requestLine.split(" ").getOrNull(1).orEmpty()
                    val headers = LinkedHashMap<String, String>()
                    var contentLength = 0
                    while (true) {
                        val headerLine = readHttpLine(input) ?: break
                        if (headerLine.isEmpty()) break
                        val colon = headerLine.indexOf(':')
                        if (colon > 0) {
                            val name = headerLine.substring(0, colon).trim().lowercase()
                            val value = headerLine.substring(colon + 1).trim()
                            headers[name] = value
                            if (name == "content-length") {
                                contentLength = value.toIntOrNull() ?: 0
                            }
                        }
                    }
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    val body = String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8)
                    val statusLine = if (acceptRequests.get()) {
                        recordedRequests += RecordedRequest(path = path, headers = headers, body = body)
                        "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                    socket.getOutputStream().write(statusLine.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                    socket.close()
                }
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val tempSelfie = File(context.cacheDir, "server_sync_test_selfie.jpg")
        val testBmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(tempSelfie).use { out ->
            testBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        testBmp.recycle()

        try {
            val store = OfflineAttendanceStore(context)
            val createdEmployees = store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = tempSelfie.absolutePath,
                        faceSignature = "0.4200:0.4800:0.6200:0.7400:0.2700:0.7500:0.7100:0.9600"
                    )
                )
            )
            val emp = createdEmployees.single()
            val log = store.recordLocalAttendance(
                employee = emp,
                eventType = AttendanceEventType.CHECK_OUT,
                verificationMethod = VerificationMethod.FACE_SCAN_1,
                confidenceScore = 96,
                loggedTimeLabel = "05:30:00 PM",
                loggedDateKey = "2026-11-18",
                synced = false,
                gpsCoordinates = "24.860734, 67.001136 (±7m)",
                locationAddress = "NICH Campus, Karachi",
                timeSource = "NTP Verified (time.google.com)"
            )
            store.persistSelfieForLog(log.id, tempSelfie.absolutePath)
            store.markServerEmployeePending(emp.id)
            store.markServerLogPending(
                logId = log.id,
                ntpEpochMillis = 1_794_999_000_000L,
                emailDispatchStatus = "SENT"
            )
            store.enqueueServerEmailReport(
                PendingServerEmailReport(
                    reportId = "email_${log.id}",
                    logId = log.id,
                    employeeId = emp.id,
                    employeeCode = emp.employeeCode,
                    employeeName = emp.name,
                    department = emp.department,
                    eventType = AttendanceEventType.CHECK_OUT.wireValue,
                    recipientEmail = "Nichemployeeattendance@gmail.com",
                    dispatchStatus = "SENT",
                    pdfFileName = "NICH_${emp.employeeCode}_2026-11-18.pdf",
                    gpsCoordinates = log.gpsCoordinates,
                    locationAddress = log.locationAddress,
                    timeSource = log.timeSource,
                    ntpEpochMillis = 1_794_999_000_000L
                )
            )

            val fakePubKey = "sb_publishable_test_dummy_key_001"
            val fakeSecKey = "sb_secret_test_dummy_key_001"
            val client = ServerSyncClient(
                serverUrlProvider = { "http://127.0.0.1:${serverSocket.localPort}" },
                publishableKeyProvider = { fakePubKey },
                secretKeyProvider = { fakeSecKey }
            )
            assertTrue(client.isConfigured())

            // 1. Offline / 503 failure MUST retain all pending queues without data loss
            val offlineSummary = client.flushPending(store, operatorUid = "op_test_1")
            assertTrue(offlineSummary.configured)
            assertEquals(0, offlineSummary.syncedLogs)
            assertTrue(emp.id in store.pendingServerEmployeeIds.value)
            assertTrue(log.id in store.pendingServerLogIds.value)
            assertEquals(1, store.getPendingServerEmailReports().size)

            // 2. Re-create OfflineAttendanceStore (simulating app restart) and restore server
            val restoredStore = OfflineAttendanceStore(context)
            assertTrue(log.id in restoredStore.pendingServerLogIds.value)
            acceptRequests.set(true)

            val onlineSummary = client.flushPending(restoredStore, operatorUid = "op_test_1")
            assertTrue(onlineSummary.configured)
            assertEquals(1, onlineSummary.syncedEmployees)
            assertEquals(1, onlineSummary.syncedLogs)
            assertEquals(1, onlineSummary.syncedEmailReports)
            assertTrue(restoredStore.pendingServerEmployeeIds.value.isEmpty())
            assertTrue(restoredStore.pendingServerLogIds.value.isEmpty())
            assertTrue(restoredStore.getPendingServerEmailReports().isEmpty())

            // Verify exact Retrofit endpoints and Supabase apikey-only auth headers (no non-JWT Bearer)
            val empReq = recordedRequests.first { it.path.startsWith("/rest/v1/employees") }
            val logReq = recordedRequests.first { it.path.startsWith("/rest/v1/attendance_logs") }
            val emailReq = recordedRequests.first { it.path.startsWith("/rest/v1/email_reports") }

            listOf(empReq, logReq, emailReq).forEach { req ->
                assertEquals(fakeSecKey, req.headers["apikey"])
                assertFalse(req.headers.containsKey("authorization"))
                assertEquals("resolution=merge-duplicates,return=minimal", req.headers["prefer"])
            }

            val logJson = JSONObject(logReq.body)
            assertEquals(log.id, logJson.getString("id"))
            assertEquals("24.860734, 67.001136 (±7m)", logJson.getString("gps_coordinates"))
            assertEquals("NICH Campus, Karachi", logJson.getString("location_address"))
            assertEquals("NTP Verified (time.google.com)", logJson.getString("time_source"))
            assertEquals(1_794_999_000_000L, logJson.getLong("ntp_epoch_millis"))
            assertEquals("SENT", logJson.getString("email_dispatch_status"))
            assertTrue(logJson.getString("live_photo_base64").isNotBlank())

            val emailJson = JSONObject(emailReq.body)
            assertEquals("Nichemployeeattendance@gmail.com", emailJson.getString("recipient_email"))
            assertEquals("SENT", emailJson.getString("dispatch_status"))
        } finally {
            runCatching { serverSocket.close() }
            tempSelfie.delete()
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(StandardCharsets.US_ASCII.name())
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString(StandardCharsets.US_ASCII.name())
    }
}
