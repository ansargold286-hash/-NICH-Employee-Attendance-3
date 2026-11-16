package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalRegistrationInput
import com.example.data.local.OfflineAttendanceStore
import com.example.data.model.AttendanceEventType
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.LivenessDirection
import com.example.data.model.VerificationMethod
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.data.report.SmtpEmailSender
import com.example.ui.screens.EmployeeRegistrationScreen
import com.example.ui.screens.TerminalDashboardScreen
import com.example.ui.theme.NichAttendanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class AttendanceViewModelStrictTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    private val vectorEmployeeA =
        "0.4200:0.4800:0.6200:0.7400:0.2700:0.7500:0.7100:0.9600:0.7800:0.3400:0.3400:0.5600:2.1000:0.4500:0.2900:0.1400"
    private val vectorEmployeeB =
        "0.3400:0.3800:0.5100:0.8600:0.1900:0.5900:0.8800:0.9100:0.8900:0.4400:0.4500:0.7200:2.5500:0.6200:0.3900:0.2100"
    private val vectorStranger =
        "0.6100:0.7900:0.9500:0.5200:0.5500:1.1500:0.4800:0.7200:0.5600:0.1900:0.2000:0.9500:1.4500:0.2600:0.5800:0.3400"

    private fun morningCheckInCalendar(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 14, 9, 15, 0)
    }

    private fun afternoonCheckOutCalendar(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 14, 17, 30, 0)
    }

    private fun createTempPortrait(name: String): File {
        val file = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { stream ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        }
        bmp.recycle()
        return file
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nich_offline_attendance", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun terminalStartsScanningImmediatelyAndRemainsLiveInContinuousKioskMode() = runTest(testDispatcher) {
        val store = OfflineAttendanceStore(context)
        val vmStore = androidx.lifecycle.ViewModelStore()
        val viewModel = androidx.lifecycle.ViewModelProvider(
            vmStore,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return AttendanceViewModel(
                        offlineStore = store,
                        autoInitBackgroundJobs = true,
                        continuousKioskMode = true
                    ) as T
                }
            }
        )[AttendanceViewModel::class.java]

        try {
            runCurrent()

            val initialPhase = viewModel.terminalPhase.value
            assertTrue(initialPhase is TerminalUiPhase.Scanning)
            assertEquals(false, (initialPhase as TerminalUiPhase.Scanning).cameraReady)

            // Fresh install opens local registration flow cleanly before profiles are registered
            assertTrue(viewModel.showRegistrationScreen.value)

            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)

            viewModel.onCameraInitialized()
            runCurrent()
            assertTrue((viewModel.terminalPhase.value as TerminalUiPhase.Scanning).cameraReady)

            viewModel.onLiveFaceFrameAnalyzed(FaceAnalysisFrame(faceDetected = false))
            advanceTimeBy(20_000L)
            runCurrent()
            val standby = viewModel.terminalPhase.value
            assertTrue(standby is TerminalUiPhase.Scanning)
            assertTrue((standby as TerminalUiPhase.Scanning).cameraReady)
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun checkIn_doesNotSendEmailAndAutoResetsToScanningAfterConfirmation() = runTest(testDispatcher) {
        val dispatchCount = AtomicInteger(0)
        val smtpSender = SmtpEmailSender { _ ->
            dispatchCount.incrementAndGet()
            throw IllegalStateException("Check-In must never dispatch email")
        }
        val reporter = AttendanceEmailReporter(
            context = context,
            smtpSender = smtpSender,
            senderEmailProvider = { "Nichemployeeattendance@gmail.com" },
            appPasswordProvider = { "abcd efgh ijkl mnop" }
        )
        val store = OfflineAttendanceStore(context)
        val photo = createTempPortrait("checkin_a.jpg")
        try {
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = photo.absolutePath,
                        faceSignature = vectorEmployeeA
                    )
                )
            )
            val viewModel = AttendanceViewModel(
                offlineStore = store,
                emailReporter = reporter,
                autoInitBackgroundJobs = false,
                calendarProvider = { morningCheckInCalendar() },
                continuousKioskMode = true,
                sameEmployeeCooldownMs = 0L
            )
            runCurrent()

            viewModel.startVipBiometricScan()
            viewModel.onCameraInitialized()
            runCurrent()

            val frame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                signature = vectorEmployeeA,
                selfieImagePath = photo.absolutePath
            )
            viewModel.onLiveFaceFrameAnalyzed(frame)
            viewModel.onLiveFaceFrameAnalyzed(frame)
            runCurrent()

            val verified = viewModel.terminalPhase.value
            assertTrue(verified is TerminalUiPhase.Verified)
            verified as TerminalUiPhase.Verified
            assertEquals(AttendanceEventType.CHECK_IN, verified.eventType)
            assertEquals("Bilal Ahmed", verified.employee.name)
            assertTrue(verified.confidenceScore >= 90)
            assertEquals(0, dispatchCount.get())

            advanceTimeBy(2_600L)
            runCurrent()
            val returned = viewModel.terminalPhase.value
            assertTrue(returned is TerminalUiPhase.Scanning)
            assertEquals(0, dispatchCount.get())
        } finally {
            photo.delete()
        }
    }

    @Test
    fun faceMatching_dynamicallyMatchesDistinctLocalEmployeesAndRejectsStrangers() = runTest(testDispatcher) {
        val store = OfflineAttendanceStore(context)
        val photoA = createTempPortrait("reg_a.jpg")
        val photoB = createTempPortrait("reg_b.jpg")
        try {
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = photoA.absolutePath,
                        faceSignature = vectorEmployeeA
                    ),
                    LocalRegistrationInput(
                        name = "Dr. Ananya Rao",
                        department = "Security",
                        photoPath = photoB.absolutePath,
                        faceSignature = vectorEmployeeB
                    )
                )
            )

            val viewModel = AttendanceViewModel(
                offlineStore = store,
                autoInitBackgroundJobs = false,
                calendarProvider = { morningCheckInCalendar() },
                sameEmployeeCooldownMs = 0L
            )
            runCurrent()

            viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
            viewModel.onCameraInitialized()
            runCurrent()

            // Stranger (< 90%) must NOT verify or fall back to any employee
            val strangerFrame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                signature = vectorStranger
            )
            viewModel.onLiveFaceFrameAnalyzed(strangerFrame)
            viewModel.onLiveFaceFrameAnalyzed(strangerFrame)
            runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)

            // Scanning Employee B matches ONLY Dr. Ananya Rao with her photo
            val frameB = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                signature = vectorEmployeeB
            )
            viewModel.onLiveFaceFrameAnalyzed(frameB)
            viewModel.onLiveFaceFrameAnalyzed(frameB)
            runCurrent()

            val verifiedB = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals("Dr. Ananya Rao", verifiedB.employee.name)
            assertTrue(verifiedB.employee.localPhotoPath.isNotBlank())
            assertTrue(File(verifiedB.employee.localPhotoPath).exists())

            viewModel.resetToReady()
            runCurrent()

            // Scanning Employee A matches ONLY Bilal Ahmed with his photo
            val frameA = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                signature = vectorEmployeeA
            )
            viewModel.onLiveFaceFrameAnalyzed(frameA)
            viewModel.onLiveFaceFrameAnalyzed(frameA)
            runCurrent()

            val verifiedA = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals("Bilal Ahmed", verifiedA.employee.name)
            assertTrue(verifiedA.employee.localPhotoPath.isNotBlank())
            assertTrue(File(verifiedA.employee.localPhotoPath).exists())
        } finally {
            photoA.delete()
            photoB.delete()
        }
    }

    @Test
    fun checkOut_automaticallyTriggersSmtpTlsEmailWithPdfAndPhotoToNich() = runTest(testDispatcher) {
        val dispatchCount = AtomicInteger(0)
        val capturedRecipients = java.util.Collections.synchronizedList(mutableListOf<String>())
        val capturedMimePayloads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(
                        InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
                    )
                    val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)
                    fun reply(msg: String) {
                        writer.write("$msg\r\n")
                        writer.flush()
                    }
                    reply("220 localhost ESMTP ready")
                    var inData = false
                    var sentAuthUser = false
                    val payloadBuilder = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (inData) {
                            if (line == ".") {
                                inData = false
                                capturedMimePayloads.add(payloadBuilder.toString())
                                dispatchCount.incrementAndGet()
                                reply("250 2.0.0 OK queued")
                            } else {
                                payloadBuilder.append(line).append('\n')
                            }
                        } else {
                            when {
                                line.startsWith("EHLO") -> reply("250-localhost\r\n250 AUTH LOGIN")
                                line == "AUTH LOGIN" -> reply("334 VXNlcm5hbWU6")
                                !sentAuthUser && line != "AUTH LOGIN" -> {
                                    sentAuthUser = true
                                    reply("334 UGFzc3dvcmQ6")
                                }
                                line.startsWith("MAIL FROM:") -> reply("250 2.1.0 OK")
                                line.startsWith("RCPT TO:") -> {
                                    capturedRecipients.add(line)
                                    reply("250 2.1.5 OK")
                                }
                                line == "DATA" -> {
                                    reply("354 End data with <CR><LF>.<CR><LF>")
                                    inData = true
                                }
                                line == "QUIT" -> {
                                    reply("221 Bye")
                                    break
                                }
                                else -> reply("235 2.7.0 OK")
                            }
                        }
                    }
                    client.close()
                }
            } catch (_: Exception) {
                // Server closed cleanly
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val selfieFile = createTempPortrait("checkout_live_selfie.jpg")
        try {
            val smtpSender = SmtpEmailSender { _ ->
                Socket("127.0.0.1", serverSocket.localPort)
            }
            val reporter = AttendanceEmailReporter(
                context = context,
                smtpSender = smtpSender,
                senderEmailProvider = { "Nichemployeeattendance@gmail.com" },
                appPasswordProvider = { "abcd efgh ijkl mnop" }
            )
            val store = OfflineAttendanceStore(context)
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = selfieFile.absolutePath,
                        faceSignature = vectorEmployeeA
                    )
                )
            )
            val viewModel = AttendanceViewModel(
                offlineStore = store,
                emailReporter = reporter,
                autoInitBackgroundJobs = false,
                calendarProvider = { afternoonCheckOutCalendar() },
                continuousKioskMode = true
            )
            runCurrent()

            viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
            viewModel.onCameraInitialized()
            runCurrent()

            val frame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                signature = vectorEmployeeA,
                selfieImagePath = selfieFile.absolutePath
            )
            viewModel.onLiveFaceFrameAnalyzed(frame)
            viewModel.onLiveFaceFrameAnalyzed(frame)
            runCurrent()

            val verified = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals(AttendanceEventType.CHECK_OUT, verified.eventType)

            // Wait for automatic Check-Out SMTP/TLS dispatch to finish without requiring an extra click
            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (dispatchCount.get() < 1 && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
                runCurrent()
            }
            assertEquals(1, dispatchCount.get())
            assertTrue(capturedRecipients.any { it.contains("Nichemployeeattendance@gmail.com", ignoreCase = true) })
            val mime = capturedMimePayloads.single()
            assertTrue(mime.contains("Content-Type: application/pdf;"))
            assertTrue(mime.contains("Content-Type: image/jpeg;"))
            assertEquals(
                EmailReportStatus.SENT,
                (viewModel.terminalPhase.value as TerminalUiPhase.Verified).emailReportStatus
            )
        } finally {
            runCatching { serverSocket.close() }
            selfieFile.delete()
        }
    }

    @Test
    fun registrationUi_addsMultipleDynamicEmployeeFormsAboveSubmitButton() {
        composeRule.setContent {
            NichAttendanceTheme {
                EmployeeRegistrationScreen(
                    savedEmployees = emptyList(),
                    onRegisterBatch = {},
                    onDeleteEmployee = {},
                    onNavigateToScanner = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Employee #1").assertIsDisplayed()
        composeRule.onNodeWithTag("add_another_employee_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Employee #2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("employee_name_input_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("capture_photo_button_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("submit_registration_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun terminalUi_checkInHidesEmailButtonAndCheckOutShowsSendEmailButton() {
        val ansar = OfflineAttendanceStore.defaultPreRegisteredEmployees().first()

        val checkInPhase = TerminalUiPhase.Verified(
            employee = ansar,
            eventType = AttendanceEventType.CHECK_IN,
            verificationMethod = VerificationMethod.FACE_SCAN_1,
            confidenceScore = 96,
            timestampLabel = "09:15:00 AM",
            syncedToCloud = true
        )
        val phaseState = androidx.compose.runtime.mutableStateOf<TerminalUiPhase>(checkInPhase)

        var checkOutEmailClicks = 0
        composeRule.setContent {
            NichAttendanceTheme {
                TerminalDashboardScreen(
                    terminalPhase = phaseState.value,
                    onStartVipScan = {},
                    onCameraInitialized = {},
                    onCameraInitFailed = {},
                    onFrameAnalyzed = {},
                    onSendCheckOutEmail = { checkOutEmailClicks++ },
                    onResetTerminal = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("verification_successful_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("send_checkout_email_button").assertDoesNotExist()

        phaseState.value = checkInPhase.copy(
            eventType = AttendanceEventType.CHECK_OUT,
            timestampLabel = "05:30:00 PM"
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send_checkout_email_button").assertIsDisplayed().performClick()
        assertEquals(1, checkOutEmailClicks)
    }

    @Test
    fun sendEmailAndFinish_launchesDeviceAccountPdfIntentWhenSmtpUnconfigured() = runTest(testDispatcher) {
        val reporter = AttendanceEmailReporter(
            context = context,
            appPasswordProvider = { "MY_GMAIL_APP_PASSWORD" }
        )
        val store = OfflineAttendanceStore(context)
        val selfieFile = createTempPortrait("finish_flow_selfie.jpg")
        try {
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = selfieFile.absolutePath,
                        faceSignature = vectorEmployeeA
                    )
                )
            )
            val viewModel = AttendanceViewModel(
                offlineStore = store,
                emailReporter = reporter,
                autoInitBackgroundJobs = false,
                calendarProvider = { afternoonCheckOutCalendar() }
            )
            runCurrent()

            viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
            viewModel.onCameraInitialized()
            runCurrent()

            val frame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                signature = vectorEmployeeA,
                selfieImagePath = selfieFile.absolutePath
            )
            viewModel.onLiveFaceFrameAnalyzed(frame)
            viewModel.onLiveFaceFrameAnalyzed(frame)
            runCurrent()

            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Verified)

            var launchedIntent: Intent? = null
            viewModel.sendEmailAndFinish { intent ->
                launchedIntent = intent
            }

            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (launchedIntent == null && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
                runCurrent()
            }

            assertNotNull(launchedIntent)
            assertFalse(viewModel.showRegistrationScreen.value)
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)
        } finally {
            selfieFile.delete()
        }
    }
}
