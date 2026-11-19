package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalRegistrationInput
import com.example.data.local.OfflineAttendanceStore
import com.example.data.location.LocationAddressProvider
import com.example.data.location.VerifiedLocationSnapshot
import com.example.data.model.AttendanceEventType
import com.example.data.model.CnicScanFrame
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.LivenessDirection
import com.example.data.model.VerificationMethod
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.data.report.SmtpEmailSender
import com.example.data.time.NetworkTimeProvider
import com.example.data.time.NetworkTimeSnapshot
import com.example.data.time.SntpNetworkTimeProvider
import com.example.ui.screens.EmployeeRegistrationScreen
import com.example.ui.screens.StartupPermissionGateScreen
import com.example.ui.screens.StartupPermissionStatus
import com.example.ui.screens.TerminalDashboardScreen
import com.example.ui.theme.NichAttendanceTheme
import com.example.voice.VoicePromptScript
import com.example.voice.VoiceSpeaker
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
import org.junit.Assert.assertNull
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val vectorEmployeeAPartial50Plus =
        "0.5800:0.6400:0.7800:0.9000:0.4300:0.9100:0.8700:1.1200:0.9400:0.5000:0.5000:0.7200:1.8500:0.6100:0.4500:0.3000"
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

    private fun feedLiveHumanBlinkFrames(
        viewModel: AttendanceViewModel,
        signature: String,
        selfiePath: String? = null
    ) {
        val eyeSequence = listOf(0.88f, 0.14f, 0.90f)
        eyeSequence.forEach { eyeProb ->
            viewModel.onLiveFaceFrameAnalyzed(
                FaceAnalysisFrame(
                    faceDetected = true,
                    eyesLocked = true,
                    headPoseAligned = true,
                    leftEyeOpenProbability = eyeProb,
                    rightEyeOpenProbability = eyeProb,
                    smilingProbability = 0.12f,
                    faceCenterXNorm = 0.50f,
                    signature = signature,
                    selfieImagePath = selfiePath
                )
            )
        }
    }

    private fun feedMatchingCnicFrame(
        viewModel: AttendanceViewModel,
        employeeName: String,
        cnicNumber: String = "42101-1234567-1",
        cnicImagePath: String? = null
    ) {
        viewModel.onCnicFrameAnalyzed(
            CnicScanFrame(
                cardDetected = true,
                insideGreenFrame = true,
                rawOcrText = "PAKISTAN\nNATIONAL IDENTITY CARD\nName\n$employeeName\n$cnicNumber",
                extractedName = employeeName,
                cnicNumber = cnicNumber,
                cnicImagePath = cnicImagePath
            )
        )
    }

    @Before
    fun setUp() {
        runCatching {
            val cacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cacheField.isAccessible = true
            (cacheField.get(null) as? MutableMap<*, *>)?.clear()
        }
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

            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeA,
                selfiePath = photo.absolutePath
            )
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1",
                cnicImagePath = photo.absolutePath
            )
            runCurrent()

            val verified = viewModel.terminalPhase.value
            assertTrue(verified is TerminalUiPhase.Verified)
            verified as TerminalUiPhase.Verified
            assertEquals(AttendanceEventType.CHECK_IN, verified.eventType)
            assertEquals("Bilal Ahmed", verified.employee.name)
            assertTrue(verified.confidenceScore >= 50)
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

            // Stranger (< 50%) must NOT verify or fall back to any employee
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorStranger
            )
            runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)

            // Scanning Employee B Step A (Face) + Step B (CNIC) matches ONLY Dr. Ananya Rao
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeB
            )
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Dr. Ananya Rao",
                cnicNumber = "42201-7654321-2"
            )
            runCurrent()

            val verifiedB = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals("Dr. Ananya Rao", verifiedB.employee.name)
            assertTrue(verifiedB.employee.localPhotoPath.isNotBlank())
            assertTrue(File(verifiedB.employee.localPhotoPath).exists())

            viewModel.resetToReady()
            runCurrent()

            // Partial lighting/angle scan (~61%) + matching CNIC for Employee A marks Bilal Ahmed
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeAPartial50Plus
            )
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1"
            )
            runCurrent()

            val verifiedA = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals("Bilal Ahmed", verifiedA.employee.name)
            assertTrue(verifiedA.confidenceScore in 50..89)
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

            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeA,
                selfiePath = selfieFile.absolutePath
            )
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1",
                cnicImagePath = selfieFile.absolutePath
            )
            runCurrent()

            val verified = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals(AttendanceEventType.CHECK_OUT, verified.eventType)

            // Wait for automatic Check-Out SMTP/TLS dispatch AND coroutine resumption back to SENT state
            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (
                (dispatchCount.get() < 1 ||
                    (viewModel.terminalPhase.value as? TerminalUiPhase.Verified)?.emailReportStatus != EmailReportStatus.SENT) &&
                System.currentTimeMillis() < deadlineMs
            ) {
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
        composeRule.onNodeWithTag("open_registration_button").assertDoesNotExist()
        composeRule.onNodeWithText("Register Staff").assertDoesNotExist()
        composeRule.onNodeWithTag("verification_successful_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("send_checkout_email_button").assertDoesNotExist()

        phaseState.value = checkInPhase.copy(
            eventType = AttendanceEventType.CHECK_OUT,
            timestampLabel = "05:30:00 PM"
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open_registration_button").assertDoesNotExist()
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

            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeA,
                selfiePath = selfieFile.absolutePath
            )
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1",
                cnicImagePath = selfieFile.absolutePath
            )
            runCurrent()

            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Verified)

            var launchedIntent: Intent? = null
            viewModel.sendEmailAndFinish { intent ->
                launchedIntent = intent
            }
            runCurrent()
            val duringCountdown = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals(5, duringCountdown.emailAutoUnlockSecondsRemaining)

            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (launchedIntent == null && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
                runCurrent()
            }
            assertNotNull(launchedIntent)

            advanceTimeBy(5_100L)
            runCurrent()

            assertFalse(store.isCheckOutEmailLocked)
            assertFalse(viewModel.showRegistrationScreen.value)
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)
        } finally {
            selfieFile.delete()
        }
    }

    @Test
    fun voiceAssistant_speaksWelcomeAlignmentVerificationAndBlocksCheckOutSkipUntilEmailSent() = runTest(testDispatcher) {
        val spokenLines = mutableListOf<String>()
        val spySpeaker = object : VoiceSpeaker {
            override fun speak(message: String, flush: Boolean) {
                spokenLines += message
            }
            override fun shutdown() = Unit
        }
        val reporter = AttendanceEmailReporter(
            context = context,
            appPasswordProvider = { "MY_GMAIL_APP_PASSWORD" }
        )
        val store = OfflineAttendanceStore(context)
        val selfieFile = createTempPortrait("voice_enforce_selfie.jpg")
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
                calendarProvider = { afternoonCheckOutCalendar() },
                voiceSpeaker = spySpeaker
            )
            runCurrent()

            viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
            viewModel.onCameraInitialized()
            runCurrent()
            assertTrue(spokenLines.any { it.contains("Welcome to NICH Attendance 3", ignoreCase = true) })

            // Alignment mismatch speaks guidance prompt
            viewModel.onLiveFaceFrameAnalyzed(
                FaceAnalysisFrame(
                    faceDetected = true,
                    eyesLocked = false,
                    headPoseAligned = false
                )
            )
            runCurrent()
            assertTrue(spokenLines.any { it.contains("align your face", ignoreCase = true) })

            // Valid Check-Out recognition with live human blink + Step B CNIC scan
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeA,
                selfiePath = selfieFile.absolutePath
            )
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1",
                cnicImagePath = selfieFile.absolutePath
            )
            runCurrent()
            assertTrue(spokenLines.any { it.contains("Check-out confirmed for Bilal Ahmed", ignoreCase = true) })

            // Trying to skip / close / reset before sending email MUST be blocked
            viewModel.attemptSkipOrDismissCheckOut()
            viewModel.resetToReady()
            runCurrent()

            val blockedPhase = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertTrue(blockedPhase.emailEnforcementWarningActive)
            assertEquals(VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT, blockedPhase.emailEnforcementMessage)
            assertFalse(blockedPhase.isCheckOutEmailCompleted)
            assertTrue(spokenLines.any { it == VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT })

            // Periodic enforcement loop repeats if still unsubmitted
            advanceTimeBy(6_100L)
            runCurrent()
            assertTrue(
                spokenLines.count { it == VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT } >= 2
            )

            // Sending email starts 5-second countdown, launches intent, and unlocks return to scanner
            var intentLaunched = false
            viewModel.sendEmailAndFinish { intentLaunched = true }
            runCurrent()
            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (!intentLaunched && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
                runCurrent()
            }
            assertTrue(intentLaunched)
            advanceTimeBy(5_100L)
            runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)
        } finally {
            selfieFile.delete()
        }
    }

    @Test
    fun terminalUi_checkOutCloseAttemptDisplaysPersistentBlockingWarningBanner() {
        val ansar = OfflineAttendanceStore.defaultPreRegisteredEmployees().first()
        val initialCheckOut = TerminalUiPhase.Verified(
            employee = ansar,
            eventType = AttendanceEventType.CHECK_OUT,
            verificationMethod = VerificationMethod.FACE_SCAN_1,
            confidenceScore = 94,
            timestampLabel = "05:45:00 PM",
            syncedToCloud = false,
            emailReportStatus = EmailReportStatus.NEEDS_CONFIG,
            emailEnforcementWarningActive = false
        )
        val phaseState = androidx.compose.runtime.mutableStateOf<TerminalUiPhase>(initialCheckOut)
        var skipAttempts = 0

        composeRule.setContent {
            NichAttendanceTheme {
                TerminalDashboardScreen(
                    terminalPhase = phaseState.value,
                    onStartVipScan = {},
                    onCameraInitialized = {},
                    onCameraInitFailed = {},
                    onFrameAnalyzed = {},
                    onSendCheckOutEmail = {},
                    onResetTerminal = {},
                    onAttemptSkipCheckOutEmail = {
                        skipAttempts++
                        phaseState.value = initialCheckOut.copy(
                            emailEnforcementWarningActive = true,
                            emailEnforcementMessage = VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT
                        )
                    }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("email_enforcement_warning_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("close_checkout_overlay_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(1, skipAttempts)
        composeRule.onNodeWithTag("email_enforcement_warning_banner").assertIsDisplayed()
        composeRule.onNodeWithText(VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT).assertIsDisplayed()
    }

    @Test
    fun antiSpoofing_rejectsStaticPrintedPhotoAndPhoneScreenWithoutBlinkOrDynamicSmile() = runTest(testDispatcher) {
        val spokenLines = mutableListOf<String>()
        val spySpeaker = object : VoiceSpeaker {
            override fun speak(message: String, flush: Boolean) {
                spokenLines += message
            }
            override fun shutdown() = Unit
        }
        val store = OfflineAttendanceStore(context)
        val photo = createTempPortrait("spoof_reg.jpg")
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
                autoInitBackgroundJobs = false,
                calendarProvider = { morningCheckInCalendar() },
                voiceSpeaker = spySpeaker
            )
            runCurrent()
            viewModel.startVipBiometricScan()
            viewModel.onCameraInitialized()
            runCurrent()

            // Feed 10 completely static frames simulating a printed photo / mobile screenshot
            val staticPhotoFrame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                headPoseAligned = true,
                headYawDeg = 1.0f,
                headPitchDeg = 0.5f,
                leftEyeOpenProbability = 0.85f,
                rightEyeOpenProbability = 0.86f,
                smilingProbability = 0.72f, // Even a static smiling screenshot must fail if static!
                faceCenterXNorm = 0.50f,
                signature = vectorEmployeeA,
                selfieImagePath = photo.absolutePath
            )
            repeat(10) {
                viewModel.onLiveFaceFrameAnalyzed(staticPhotoFrame)
            }
            runCurrent()

            val phase = viewModel.terminalPhase.value
            assertTrue(phase is TerminalUiPhase.Scanning)
            phase as TerminalUiPhase.Scanning
            assertFalse(phase.livenessVerified)
            assertTrue(phase.spoofSuspected)
            assertTrue(store.logs.value.isEmpty())
            assertTrue(spokenLines.any { it.contains("Static photo or screen detected", ignoreCase = true) })
        } finally {
            photo.delete()
        }
    }

    @Test
    fun ntpLockAndGpsLocation_overridesTamperedClockAndEmbedsInPdfAndEmail() = runTest(testDispatcher) {
        val capturedBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
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
                                capturedBodies.add(payloadBuilder.toString())
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
                                line.startsWith("RCPT TO:") -> reply("250 2.1.5 OK")
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
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val selfieFile = createTempPortrait("gps_ntp_selfie.jpg")
        try {
            // Tampered phone clock says 08:00 AM (Check-In), but NTP server says 05:40 PM (Check-Out)
            val trueNtpCal = afternoonCheckOutCalendar().apply {
                set(Calendar.HOUR_OF_DAY, 17)
                set(Calendar.MINUTE, 40)
            }
            val fakeNtpProvider = object : NetworkTimeProvider {
                private val snap = NetworkTimeSnapshot(
                    epochMillis = trueNtpCal.timeInMillis,
                    sourceLabel = "NTP Verified (time.google.com) • Clock Tamper Blocked",
                    clockDriftMs = 34_800_000L,
                    tamperingDetected = true,
                    isNetworkVerified = true
                )
                override suspend fun syncNow(): NetworkTimeSnapshot = snap
                override fun currentSnapshot(): NetworkTimeSnapshot = snap
                override fun currentCalendar(): Calendar = (trueNtpCal.clone() as Calendar)
            }
            val fakeLocationProvider = object : LocationAddressProvider {
                override suspend fun captureCurrentLocation(): VerifiedLocationSnapshot =
                    VerifiedLocationSnapshot(
                        latitude = 24.860734,
                        longitude = 67.001136,
                        coordinatesLabel = "24.860734, 67.001136 (±9m)",
                        readableAddress = "NICH Campus, Rafiqui Shaheed Rd, Karachi",
                        accuracyMeters = 9f,
                        providerName = "gps"
                    )
            }

            val smtpSender = SmtpEmailSender { _ -> Socket("127.0.0.1", serverSocket.localPort) }
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
                calendarProvider = { morningCheckInCalendar() },
                networkTimeProvider = fakeNtpProvider,
                locationProvider = fakeLocationProvider
            )
            runCurrent()
            viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
            viewModel.onCameraInitialized()
            runCurrent()

            feedLiveHumanBlinkFrames(viewModel, vectorEmployeeA, selfieFile.absolutePath)
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1",
                cnicImagePath = selfieFile.absolutePath
            )
            runCurrent()

            val verified = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            // Proven NTP override: afternoon CHECK_OUT recorded despite 09:15 AM device clock!
            assertEquals(AttendanceEventType.CHECK_OUT, verified.eventType)
            assertEquals("24.860734, 67.001136 (±9m)", verified.gpsCoordinates)
            assertEquals("NICH Campus, Rafiqui Shaheed Rd, Karachi", verified.locationAddress)
            assertTrue(verified.timeSourceLabel.contains("NTP Verified"))

            val savedLog = store.logs.value.first()
            assertEquals("24.860734, 67.001136 (±9m)", savedLog.gpsCoordinates)
            assertEquals("NICH Campus, Rafiqui Shaheed Rd, Karachi", savedLog.locationAddress)
            assertTrue(savedLog.timeSource.contains("NTP Verified"))

            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (capturedBodies.isEmpty() && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
                runCurrent()
            }
            assertEquals(1, capturedBodies.size)
            val mime = capturedBodies.single()
            assertTrue(mime.contains("Content-Type: application/pdf;"))
            assertTrue(mime.contains("Content-Type: image/jpeg;"))
            val textPartMarker = "Content-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: base64\n\n"
            val startIdx = mime.indexOf(textPartMarker)
            assertTrue(startIdx >= 0)
            val bodyAfterHeader = mime.substring(startIdx + textPartMarker.length)
            val encodedTextBody = bodyAfterHeader.substringBefore("\n--====NICH_ATT_")
            val decodedEmailBody = String(
                android.util.Base64.decode(encodedTextBody, android.util.Base64.DEFAULT),
                StandardCharsets.UTF_8
            )
            assertTrue(decodedEmailBody.contains("24.860734, 67.001136"))
            assertTrue(decodedEmailBody.contains("NICH Campus, Rafiqui Shaheed Rd, Karachi"))
            assertTrue(decodedEmailBody.contains("NTP Verified"))
        } finally {
            runCatching { serverSocket.close() }
            selfieFile.delete()
        }
    }

    @Test
    fun sntpClient_parsesRealUdpNtpResponseAndIgnoresPostSyncPhoneClockTampering() = runTest(testDispatcher) {
        val authoritativeNtpEpochMs = 1_794_900_000_000L // Deterministic 2026 epoch ms
        val udpServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                val reqBuf = ByteArray(48)
                val reqPacket = DatagramPacket(reqBuf, reqBuf.size)
                udpServer.receive(reqPacket)

                val respBuf = ByteArray(48)
                respBuf[0] = 0x1C // LI=0, VN=3, Mode=4 (Server)
                // Copy client's transmit timestamp (40..47) into originate timestamp (24..31)
                System.arraycopy(reqBuf, 40, respBuf, 24, 8)
                SntpNetworkTimeProvider.writeTimeStamp(respBuf, 32, authoritativeNtpEpochMs)
                SntpNetworkTimeProvider.writeTimeStamp(respBuf, 40, authoritativeNtpEpochMs)

                val respPacket = DatagramPacket(
                    respBuf,
                    respBuf.size,
                    reqPacket.address,
                    reqPacket.port
                )
                udpServer.send(respPacket)
            } catch (_: Exception) {
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            // Simulate user manually rolling phone wall clock backward by 6 hours
            val deviceWallMs = AtomicLong(authoritativeNtpEpochMs - (6 * 3600 * 1000L))
            val monotonicElapsedMs = AtomicLong(10_000L)

            val provider = SntpNetworkTimeProvider(
                ntpHosts = listOf("127.0.0.1"),
                ntpPort = udpServer.localPort,
                timeoutMs = 2000,
                allowHttpFallback = false,
                wallClockMs = { deviceWallMs.get() },
                elapsedRealtimeMs = { monotonicElapsedMs.get() },
                timeZoneProvider = { TimeZone.getTimeZone("UTC") }
            )

            val synced = provider.syncNow()
            assertTrue(synced.isNetworkVerified)
            assertTrue(synced.tamperingDetected)
            assertTrue(kotlin.math.abs(synced.epochMillis - authoritativeNtpEpochMs) < 1_000L)

            // Even if user alters phone clock further AND monotonic uptime advances 5 seconds:
            monotonicElapsedMs.addAndGet(5_000L)
            deviceWallMs.addAndGet(-3_600_000L)

            val afterTamper = provider.currentSnapshot()
            assertTrue(afterTamper.tamperingDetected)
            assertTrue(afterTamper.sourceLabel.contains("Clock Tamper Blocked"))
            assertTrue(kotlin.math.abs(afterTamper.epochMillis - (authoritativeNtpEpochMs + 5_000L)) < 1_000L)
        } finally {
            runCatching { udpServer.close() }
        }
    }

    @Test
    fun startupPermissionGate_blocksStartupSpeaksWarningAndOpensSystemSettings() {
        var warningSpokenCount = 0
        var settingsOpenedCount = 0
        var refreshedCount = 0

        composeRule.setContent {
            NichAttendanceTheme {
                StartupPermissionGateScreen(
                    status = StartupPermissionStatus(cameraGranted = false, locationGranted = false),
                    onRefreshPermissions = { refreshedCount++ },
                    onSpeakPermissionWarning = { warningSpokenCount++ },
                    onOpenSystemSettings = { settingsOpenedCount++ },
                    autoRequestOnLaunch = false
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("startup_permission_gate_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("permission_voice_warning_banner").assertIsDisplayed()
        composeRule.onNodeWithText(VoicePromptScript.REQUIRED_PERMISSIONS_WARNING_PROMPT).assertIsDisplayed()
        assertTrue(warningSpokenCount >= 1)

        composeRule.onNodeWithTag("open_app_settings_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, settingsOpenedCount)
        assertTrue(warningSpokenCount >= 2)
    }

    @Test
    fun persistentCheckOutLock_survivesAppRestart_blocksNewCheckInsUntilSmtpSucceeds() = runTest(testDispatcher) {
        val spokenLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val spySpeaker = object : VoiceSpeaker {
            override fun speak(message: String, flush: Boolean) {
                spokenLines += message
            }
            override fun shutdown() = Unit
        }
        val smtpNetworkReachable = java.util.concurrent.atomic.AtomicBoolean(false)
        val receivedPayloads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
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
                                receivedPayloads.add(payloadBuilder.toString())
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
                                line.startsWith("RCPT TO:") -> reply("250 2.1.5 OK")
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
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val selfieFile = createTempPortrait("persistent_lock_selfie.jpg")
        try {
            val store1 = OfflineAttendanceStore(context)
            store1.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = selfieFile.absolutePath,
                        faceSignature = vectorEmployeeA
                    )
                )
            )

            val smtpSender = SmtpEmailSender { _ ->
                if (!smtpNetworkReachable.get()) {
                    throw java.io.IOException("Simulated offline network during Check-Out")
                }
                Socket("127.0.0.1", serverSocket.localPort)
            }
            val reporter1 = AttendanceEmailReporter(
                context = context,
                smtpSender = smtpSender,
                senderEmailProvider = { "Nichemployeeattendance@gmail.com" },
                appPasswordProvider = { "abcd efgh ijkl mnop" }
            )

            val vm1Store = androidx.lifecycle.ViewModelStore()
            val vm1 = androidx.lifecycle.ViewModelProvider(
                vm1Store,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return AttendanceViewModel(
                            offlineStore = store1,
                            emailReporter = reporter1,
                            autoInitBackgroundJobs = false,
                            calendarProvider = { afternoonCheckOutCalendar() },
                            voiceSpeaker = spySpeaker
                        ) as T
                    }
                }
            )[AttendanceViewModel::class.java]
            runCurrent()
            vm1.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
            vm1.onCameraInitialized()
            runCurrent()
            feedLiveHumanBlinkFrames(vm1, vectorEmployeeA, selfieFile.absolutePath)
            assertTrue(vm1.terminalPhase.value is TerminalUiPhase.CnicScanning)
            feedMatchingCnicFrame(
                viewModel = vm1,
                employeeName = "Bilal Ahmed",
                cnicNumber = "42101-1234567-1",
                cnicImagePath = selfieFile.absolutePath
            )
            runCurrent()

            val lockDeadline = System.currentTimeMillis() + 5_000L
            while (store1.pendingCheckOutLock.value == null && System.currentTimeMillis() < lockDeadline) {
                Thread.sleep(20L)
                runCurrent()
            }
            // Wait until failed SMTP settles to QUEUED_OFFLINE
            while (
                (vm1.terminalPhase.value as? TerminalUiPhase.Verified)?.emailReportStatus != EmailReportStatus.QUEUED_OFFLINE &&
                System.currentTimeMillis() < lockDeadline
            ) {
                Thread.sleep(20L)
                runCurrent()
            }
            assertTrue(store1.isCheckOutEmailLocked)
            assertEquals(1, store1.logs.value.size)

            // 2. Simulate app kill & cold relaunch next morning (09:15 AM)
            vm1Store.clear()
            spokenLines.clear()
            val storeAfterKill = OfflineAttendanceStore(context)
            assertTrue(storeAfterKill.isCheckOutEmailLocked)
            val reporter2 = AttendanceEmailReporter(
                context = context,
                smtpSender = smtpSender,
                senderEmailProvider = { "Nichemployeeattendance@gmail.com" },
                appPasswordProvider = { "abcd efgh ijkl mnop" }
            )
            val vmAfterRestartStore = androidx.lifecycle.ViewModelStore()
            val vmAfterRestart = androidx.lifecycle.ViewModelProvider(
                vmAfterRestartStore,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return AttendanceViewModel(
                            offlineStore = storeAfterKill,
                            emailReporter = reporter2,
                            autoInitBackgroundJobs = false,
                            calendarProvider = { morningCheckInCalendar() },
                            voiceSpeaker = spySpeaker
                        ) as T
                    }
                }
            )[AttendanceViewModel::class.java]
            try {
                runCurrent()

                val lockedPhase = vmAfterRestart.terminalPhase.value as TerminalUiPhase.Verified
                assertTrue(lockedPhase.isPersistentLock)
                assertTrue(lockedPhase.emailEnforcementWarningActive)
                assertEquals(VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT, lockedPhase.emailEnforcementMessage)

                // 3. Attempting new Check-In scan/frames BEFORE tapping Send Email MUST be blocked & speak lock prompt
                vmAfterRestart.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
                feedLiveHumanBlinkFrames(vmAfterRestart, vectorEmployeeA, selfieFile.absolutePath)
                vmAfterRestart.openRegistrationScreen()
                vmAfterRestart.resetToReady()
                runCurrent()

                assertTrue(vmAfterRestart.terminalPhase.value is TerminalUiPhase.Verified)
                assertFalse(vmAfterRestart.showRegistrationScreen.value)
                assertEquals(1, storeAfterKill.logs.value.size)
                assertTrue(spokenLines.any { it == VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT })

                // 4. Restore network & tap Send Email -> SMTP dispatches & 5-second countdown clears lock cleanly
                smtpNetworkReachable.set(true)
                vmAfterRestart.sendEmailAndFinish { }
                runCurrent()
                assertEquals(
                    5,
                    (vmAfterRestart.terminalPhase.value as? TerminalUiPhase.Verified)?.emailAutoUnlockSecondsRemaining
                )

                val smtpDeadline = System.currentTimeMillis() + 5_000L
                while (receivedPayloads.isEmpty() && System.currentTimeMillis() < smtpDeadline) {
                    Thread.sleep(25L)
                    runCurrent()
                }
                advanceTimeBy(5_100L)
                runCurrent()

                assertFalse(storeAfterKill.isCheckOutEmailLocked)
                assertNull(OfflineAttendanceStore(context).pendingCheckOutLock.value)
                assertEquals(1, receivedPayloads.size)
                assertTrue(vmAfterRestart.terminalPhase.value is TerminalUiPhase.Scanning)
            } finally {
                vmAfterRestartStore.clear()
            }
        } finally {
            runCatching { serverSocket.close() }
            selfieFile.delete()
        }
    }

    @Test
    fun terminalUi_persistentCheckOutLockDisplaysWarningBannerAndOfflineSavedBadge() {
        val ansar = OfflineAttendanceStore.defaultPreRegisteredEmployees().first()
        val lockedPhase = TerminalUiPhase.Verified(
            employee = ansar,
            eventType = AttendanceEventType.CHECK_OUT,
            verificationMethod = VerificationMethod.FACE_SCAN_1,
            confidenceScore = 95,
            timestampLabel = "05:50:00 PM",
            syncedToCloud = false,
            logId = "log_locked_ui_1",
            isPersistentLock = true,
            emailReportStatus = EmailReportStatus.QUEUED_OFFLINE,
            emailEnforcementWarningActive = true,
            emailEnforcementMessage = VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT,
            checkOutEmailTriggeredOrSent = false
        )

        composeRule.setContent {
            NichAttendanceTheme {
                TerminalDashboardScreen(
                    terminalPhase = lockedPhase,
                    onStartVipScan = {},
                    onCameraInitialized = {},
                    onCameraInitFailed = {},
                    onFrameAnalyzed = {},
                    onSendCheckOutEmail = {},
                    onResetTerminal = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("email_enforcement_warning_banner").assertIsDisplayed()
        composeRule.onNodeWithText(VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT).assertIsDisplayed()
        composeRule.onNodeWithTag("offline_lock_saved_badge").assertIsDisplayed()
        composeRule.onNodeWithTag("send_checkout_email_button").assertIsDisplayed()
    }

    @Test
    fun dualVerification_rejectsWrongEmployeeCnicAndSpeaksVoiceAlert() = runTest(testDispatcher) {
        val spokenLines = mutableListOf<String>()
        val spySpeaker = object : VoiceSpeaker {
            override fun speak(message: String, flush: Boolean) {
                spokenLines += message
            }
            override fun shutdown() = Unit
        }
        val store = OfflineAttendanceStore(context)
        val photo = createTempPortrait("cnic_reject.jpg")
        try {
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = photo.absolutePath,
                        faceSignature = vectorEmployeeA,
                        cnicPhotoPath = photo.absolutePath,
                        cnicNumber = "42101-1234567-1",
                        cnicName = "Bilal Ahmed"
                    )
                )
            )
            val viewModel = AttendanceViewModel(
                offlineStore = store,
                autoInitBackgroundJobs = false,
                calendarProvider = { morningCheckInCalendar() },
                voiceSpeaker = spySpeaker
            )
            runCurrent()
            viewModel.startVipBiometricScan()
            viewModel.onCameraInitialized()
            runCurrent()

            feedLiveHumanBlinkFrames(viewModel, vectorEmployeeA, photo.absolutePath)
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)

            // Scan wrong person's CNIC -> MUST reject Step B cleanly and keep attendance unrecorded
            feedMatchingCnicFrame(
                viewModel = viewModel,
                employeeName = "Random Stranger",
                cnicNumber = "42201-9999999-9",
                cnicImagePath = photo.absolutePath
            )
            runCurrent()

            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
            assertTrue(store.logs.value.isEmpty())
            assertTrue(spokenLines.any { it.contains("CNIC name does not match Bilal Ahmed", ignoreCase = true) })
        } finally {
            photo.delete()
        }
    }

    @Test
    fun multiEmployeeBatchMode_gatesSendEmailUntilAllStaffCompleteCheckInAndCheckOut_thenDispatchesConsolidatedPdf() = runTest(testDispatcher) {
        val sentPayloads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            try {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
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
                                sentPayloads.add(payloadBuilder.toString())
                                payloadBuilder.setLength(0)
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
                                line.startsWith("RCPT TO:") -> reply("250 2.1.5 OK")
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
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val photoA = createTempPortrait("batch_a.jpg")
        val photoB = createTempPortrait("batch_b.jpg")
        val currentClock = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.NOVEMBER, 18, 9, 0, 0)
        }
        try {
            val smtpSender = SmtpEmailSender { _ -> Socket("127.0.0.1", serverSocket.localPort) }
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
                        photoPath = photoA.absolutePath,
                        faceSignature = vectorEmployeeA,
                        cnicPhotoPath = photoA.absolutePath,
                        cnicNumber = "42101-1111111-1",
                        cnicName = "Bilal Ahmed"
                    ),
                    LocalRegistrationInput(
                        name = "Dr. Ananya Rao",
                        department = "Security",
                        photoPath = photoB.absolutePath,
                        faceSignature = vectorEmployeeB,
                        cnicPhotoPath = photoB.absolutePath,
                        cnicNumber = "42201-2222222-2",
                        cnicName = "Dr. Ananya Rao"
                    )
                )
            )
            val viewModel = AttendanceViewModel(
                offlineStore = store,
                emailReporter = reporter,
                autoInitBackgroundJobs = false,
                calendarProvider = { currentClock.clone() as Calendar },
                continuousKioskMode = true,
                sameEmployeeCooldownMs = 0L
            )
            runCurrent()

            fun performScan(sig: String, name: String, cnic: String, imgPath: String) {
                viewModel.startVipBiometricScan()
                viewModel.onCameraInitialized()
                runCurrent()
                feedLiveHumanBlinkFrames(viewModel, sig, imgPath)
                assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)
                feedMatchingCnicFrame(viewModel, name, cnic, imgPath)
                runCurrent()
            }

            // Morning 09:00 AM Check-In for both staff
            performScan(vectorEmployeeA, "Bilal Ahmed", "42101-1111111-1", photoA.absolutePath)
            assertEquals(AttendanceEventType.CHECK_IN, (viewModel.terminalPhase.value as TerminalUiPhase.Verified).eventType)
            advanceTimeBy(2_600L); runCurrent()

            performScan(vectorEmployeeB, "Dr. Ananya Rao", "42201-2222222-2", photoB.absolutePath)
            assertEquals(AttendanceEventType.CHECK_IN, (viewModel.terminalPhase.value as TerminalUiPhase.Verified).eventType)
            advanceTimeBy(2_600L); runCurrent()
            assertEquals(0, sentPayloads.size)

            // Evening 05:30 PM Check-Out #1 (Bilal only): Email MUST stay gated/disabled & NO lock blocks Employee #2!
            currentClock.set(Calendar.HOUR_OF_DAY, 17)
            currentClock.set(Calendar.MINUTE, 30)
            performScan(vectorEmployeeA, "Bilal Ahmed", "42101-1111111-1", photoA.absolutePath)
            val midOut = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertTrue(midOut.isBatchMode)
            assertEquals(1, midOut.batchCompletedCount)
            assertEquals(2, midOut.batchTotalCount)
            assertFalse(midOut.batchAllCompleted)
            assertEquals(listOf("Dr. Ananya Rao"), midOut.batchPendingEmployeeNames)
            assertFalse(store.isCheckOutEmailLocked)
            // Attempting Send Email while batch incomplete is a no-op
            viewModel.sendEmailAndFinish { }
            runCurrent()
            assertEquals(0, sentPayloads.size)

            // Returns automatically to scanner for Employee #2
            advanceTimeBy(2_600L); runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)

            // Evening Check-Out #2 (Ananya finishes): Consolidated Batch PDF enabled & auto-sent!
            performScan(vectorEmployeeB, "Dr. Ananya Rao", "42201-2222222-2", photoB.absolutePath)
            val finalOut = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertTrue(finalOut.isBatchMode)
            assertEquals(2, finalOut.batchCompletedCount)
            assertEquals(2, finalOut.batchTotalCount)
            assertTrue(finalOut.batchAllCompleted)
            assertTrue(store.isCheckOutEmailLocked)

            val smtpDeadline = System.currentTimeMillis() + 5_000L
            while (
                (sentPayloads.isEmpty() ||
                    (viewModel.terminalPhase.value as? TerminalUiPhase.Verified)?.emailReportStatus != EmailReportStatus.SENT) &&
                System.currentTimeMillis() < smtpDeadline
            ) {
                Thread.sleep(25L)
                runCurrent()
            }
            assertEquals(1, sentPayloads.size)
            val mime = sentPayloads.single()
            assertTrue(mime.contains("Content-Type: application/pdf;"))
            assertTrue(mime.contains("Content-Type: image/jpeg;"))
            val rawSubject = mime.lines().firstOrNull { it.startsWith("Subject:") }.orEmpty()
            val decodedSubject = if (rawSubject.contains("=?UTF-8?B?")) {
                val b64 = rawSubject.substringAfter("=?UTF-8?B?").substringBefore("?=")
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), StandardCharsets.UTF_8)
            } else {
                rawSubject
            }
            assertTrue(decodedSubject.contains("CONSOLIDATED BATCH REPORT"))

            val textPartMarker = "Content-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: base64\n\n"
            val startIdx = mime.indexOf(textPartMarker)
            assertTrue(startIdx >= 0)
            val encodedTextBody = mime.substring(startIdx + textPartMarker.length).substringBefore("\n--====NICH_ATT_")
            val decodedEmailBody = String(
                android.util.Base64.decode(encodedTextBody, android.util.Base64.DEFAULT),
                StandardCharsets.UTF_8
            )
            assertTrue(decodedEmailBody.contains("Consolidated Multi-Employee Daily Report"))
            assertTrue(decodedEmailBody.contains("Completed Staff Cycles: 2/2"))
            assertTrue(decodedEmailBody.contains("Bilal Ahmed"))
            assertTrue(decodedEmailBody.contains("Dr. Ananya Rao"))
            assertFalse(store.isCheckOutEmailLocked)
        } finally {
            runCatching { serverSocket.close() }
            photoA.delete()
            photoB.delete()
        }
    }

    @Test
    fun pendingCheckOutLock_fiveSecondCountdownOnSendEmailAutoClearsLockEvenWithoutSmtp() = runTest(testDispatcher) {
        val reporter = AttendanceEmailReporter(
            context = context,
            appPasswordProvider = { "MY_GMAIL_APP_PASSWORD" }
        )
        val store = OfflineAttendanceStore(context)
        val photo = createTempPortrait("countdown_unlock.jpg")
        try {
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        department = "Engineering",
                        photoPath = photo.absolutePath,
                        faceSignature = vectorEmployeeA,
                        cnicPhotoPath = photo.absolutePath,
                        cnicNumber = "42101-1234567-1",
                        cnicName = "Bilal Ahmed"
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
            viewModel.startVipBiometricScan()
            viewModel.onCameraInitialized()
            runCurrent()

            feedLiveHumanBlinkFrames(viewModel, vectorEmployeeA, photo.absolutePath)
            feedMatchingCnicFrame(viewModel, "Bilal Ahmed", "42101-1234567-1", photo.absolutePath)
            runCurrent()

            assertTrue(store.isCheckOutEmailLocked)

            // Tapping Send Email launches intent AND starts 5-second countdown
            viewModel.sendEmailAndFinish { }
            runCurrent()
            val tick5 = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals(5, tick5.emailAutoUnlockSecondsRemaining)
            assertTrue(store.isCheckOutEmailLocked)

            advanceTimeBy(3_000L)
            runCurrent()
            val tick2 = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals(2, tick2.emailAutoUnlockSecondsRemaining)
            assertTrue(store.isCheckOutEmailLocked)

            advanceTimeBy(2_100L)
            runCurrent()

            // After 5 seconds total: pending email lock is cleared & terminal returns to Scanning
            assertFalse(store.isCheckOutEmailLocked)
            assertNull(store.pendingCheckOutLock.value)
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)
        } finally {
            photo.delete()
        }
    }

    @Test
    fun verificationCard_hidesRegisteredPhotoShowsOnlyLiveSelfieCnicCardAndCleanCnicName() = runTest(testDispatcher) {
        val regPhoto = createTempPortrait("reg_ref_hidden.jpg")
        val liveSelfie = createTempPortrait("live_selfie_shown.jpg")
        val cnicCard = createTempPortrait("live_cnic_shown.jpg")
        val employee = com.example.data.model.Employee(
            id = "emp_ansar",
            employeeCode = "NICH-4001",
            name = "محمد انصار (Muhammad Ansar)",
            department = "Security",
            localPhotoPath = regPhoto.absolutePath,
            cnicNumber = "42101-9876543-1",
            cnicName = "Muhammad Ansar"
        )
        try {
            composeRule.setContent {
                NichAttendanceTheme {
                    TerminalDashboardScreen(
                        terminalPhase = TerminalUiPhase.Verified(
                            employee = employee,
                            eventType = AttendanceEventType.CHECK_IN,
                            verificationMethod = VerificationMethod.FACE_SCAN_1,
                            confidenceScore = 95,
                            timestampLabel = "09:15:00 AM",
                            syncedToCloud = true,
                            logId = "log_clean_ui",
                            liveSelfiePath = liveSelfie.absolutePath,
                            matchedCnicName = "OREAM",
                            matchedCnicNumber = "42101-9876543-1",
                            cnicPhotoPath = cnicCard.absolutePath
                        ),
                        onStartVipScan = {},
                        onCameraInitialized = {},
                        onCameraInitFailed = {},
                        onFrameAnalyzed = {},
                        onSendCheckOutEmail = {},
                        onResetTerminal = {}
                    )
                }
            }

            composeRule.onNodeWithTag("verification_successful_overlay").assertIsDisplayed()
            composeRule.onNodeWithTag("live_captured_face_photo").assertIsDisplayed()
            composeRule.onNodeWithTag("live_captured_cnic_photo").assertIsDisplayed()
            composeRule.onNodeWithTag("verified_employee_name").assertIsDisplayed()
            composeRule.onNodeWithText("Muhammad Ansar").assertIsDisplayed()
            composeRule.onNodeWithText("CNIC Verified: Muhammad Ansar • 42101-9876543-1").assertIsDisplayed()
            composeRule.onNodeWithText("OREAM").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Registered profile photo").assertDoesNotExist()
        } finally {
            regPhoto.delete()
            liveSelfie.delete()
            cnicCard.delete()
        }
    }

    @Test
    fun cnicStepWithGarbledOcrName_resolvesRealCnicNameInAttendanceLogAndTts() = runTest(testDispatcher) {
        val store = OfflineAttendanceStore(context)
        val spokenPrompts = mutableListOf<String>()
        val voiceRecorder = object : VoiceSpeaker {
            override fun speak(message: String, flush: Boolean) {
                spokenPrompts += message
            }
            override fun shutdown() = Unit
        }
        val regPhoto = createTempPortrait("reg_ansar.jpg")
        val livePhoto = createTempPortrait("live_ansar.jpg")
        try {
            store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "محمد انصار (Muhammad Ansar)",
                        department = "Engineering",
                        photoPath = regPhoto.absolutePath,
                        faceSignature = vectorEmployeeA,
                        cnicPhotoPath = regPhoto.absolutePath,
                        cnicNumber = "42101-9876543-1",
                        cnicName = "a" // simulate bad earlier OCR attempt during registration fallback
                    )
                )
            )
            val viewModel = AttendanceViewModel(
                offlineStore = store,
                autoInitBackgroundJobs = false,
                calendarProvider = { morningCheckInCalendar() },
                voiceSpeaker = voiceRecorder
            )
            runCurrent()
            viewModel.startVipBiometricScan()
            viewModel.onCameraInitialized()
            runCurrent()

            feedLiveHumanBlinkFrames(viewModel, vectorEmployeeA, livePhoto.absolutePath)
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.CnicScanning)

            // Noisy OCR with "OREAM" & "a" but valid matching CNIC number
            viewModel.onCnicFrameAnalyzed(
                CnicScanFrame(
                    cardDetected = true,
                    insideGreenFrame = true,
                    rawOcrText = "PAKISTAN\nOREAM\nName\na\n42101-9876543-1",
                    extractedName = "a",
                    cnicNumber = "42101-9876543-1",
                    cnicImagePath = livePhoto.absolutePath
                )
            )
            runCurrent()

            val verified = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals("Muhammad Ansar", verified.matchedCnicName)
            assertFalse(verified.matchedCnicName.equals("a", ignoreCase = true))
            assertFalse(verified.matchedCnicName.contains("OREAM"))

            val logged = store.logs.value.first()
            assertEquals("Muhammad Ansar", logged.employeeName)
            assertEquals("Muhammad Ansar", logged.matchedCnicName)
            assertTrue(spokenPrompts.any { it.contains("Muhammad Ansar") })
        } finally {
            regPhoto.delete()
            livePhoto.delete()
        }
    }
}
