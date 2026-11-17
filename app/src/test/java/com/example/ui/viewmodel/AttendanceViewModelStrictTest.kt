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
import com.example.data.location.LocationAddressProvider
import com.example.data.location.VerifiedLocationSnapshot
import com.example.data.model.AttendanceEventType
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

            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeA,
                selfiePath = photo.absolutePath
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

            // Scanning Employee B matches ONLY Dr. Ananya Rao with her photo
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeB
            )
            runCurrent()

            val verifiedB = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals("Dr. Ananya Rao", verifiedB.employee.name)
            assertTrue(verifiedB.employee.localPhotoPath.isNotBlank())
            assertTrue(File(verifiedB.employee.localPhotoPath).exists())

            viewModel.resetToReady()
            runCurrent()

            // Partial lighting/angle scan (~61%) for Employee A marks Bilal Ahmed seamlessly at >= 50%
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeAPartial50Plus
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

            // Valid Check-Out recognition with live human blink
            feedLiveHumanBlinkFrames(
                viewModel = viewModel,
                signature = vectorEmployeeA,
                selfiePath = selfieFile.absolutePath
            )
            runCurrent()
            assertTrue(spokenLines.any { it.contains("Check-out verified for Bilal Ahmed", ignoreCase = true) })

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

            // Sending email completes Check-Out and unlocks return to scanner
            var intentLaunched = false
            viewModel.sendEmailAndFinish { intentLaunched = true }
            val deadlineMs = System.currentTimeMillis() + 5_000L
            while (!intentLaunched && System.currentTimeMillis() < deadlineMs) {
                Thread.sleep(25L)
                runCurrent()
            }
            assertTrue(intentLaunched)
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
}
