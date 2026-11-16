package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OfflineAttendanceStore
import com.example.data.model.AttendanceEventType
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.LivenessDirection
import com.example.data.model.VerificationMethod
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.data.report.SmtpEmailSender
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
@Config(sdk = [36])
class AttendanceViewModelStrictTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    private fun morningCheckInCalendar(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 14, 9, 15, 0)
    }

    private fun afternoonCheckOutCalendar(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.NOVEMBER, 14, 17, 30, 0)
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

            // Advance clock 10 seconds while camera is initializing: must NOT time out early
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)

            // CameraX binds -> remains live in continuous standby without manual retry buttons
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

        viewModel.onLiveFaceFrameAnalyzed(
            FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                signature = OfflineAttendanceStore.DEFAULT_ANSAR_FACE_SIGNATURE,
                selfieImagePath = "/tmp/live_selfie_auto_return.jpg"
            )
        )
        runCurrent()

        val verified = viewModel.terminalPhase.value
        assertTrue(verified is TerminalUiPhase.Verified)
        assertEquals(AttendanceEventType.CHECK_IN, (verified as TerminalUiPhase.Verified).eventType)
        assertEquals(0, dispatchCount.get())

        // After short ~2.5s Verification Successful overlay, automatically returns to terminal-ready Scanning mode
        advanceTimeBy(2_600L)
        runCurrent()
        val returned = viewModel.terminalPhase.value
        assertTrue(returned is TerminalUiPhase.Scanning)
        assertTrue((returned as TerminalUiPhase.Scanning).cameraReady)
        assertEquals(0, dispatchCount.get())
    }

    @Test
    fun vipScan_acceptsMinimalOnePercentPartialFaceSimilarityImmediatelyWithSelfie() = runTest {
        val store = OfflineAttendanceStore(context)
        val viewModel = AttendanceViewModel(
            offlineStore = store,
            autoInitBackgroundJobs = false
        )
        runCurrent()

        viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
        viewModel.onCameraInitialized()
        runCurrent()

        // Even a distant/minimal facial correlation frame must match instantly (>= 1%) on the very first frame
        viewModel.onLiveFaceFrameAnalyzed(
            FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                noseMustacheLocked = false,
                jawBeardLocked = false,
                signature = "0.1500:0.1500:0.2000:0.4500:0.1000:0.3000:0.4000:0.5500",
                selfieImagePath = "/tmp/live_selfie_minimal_match.jpg"
            )
        )
        runCurrent()

        val verified = viewModel.terminalPhase.value
        assertTrue(verified is TerminalUiPhase.Verified)
        verified as TerminalUiPhase.Verified
        assertTrue(verified.confidenceScore >= 1)
        assertEquals("/tmp/live_selfie_minimal_match.jpg", verified.liveSelfiePath)
        assertTrue(store.logs.value.isNotEmpty())

        viewModel.resetToReady()
        runCurrent()
    }

    @Test
    fun vipScan_completesDirectionalLivenessSequenceAndAutoVerifiesWithLiveSelfie() = runTest {
        val store = OfflineAttendanceStore(context)
        val viewModel = AttendanceViewModel(
            offlineStore = store,
            autoInitBackgroundJobs = false
        )
        runCurrent()

        viewModel.startVipBiometricScan(
            listOf(
                LivenessDirection.TURN_LEFT,
                LivenessDirection.LOOK_UP,
                LivenessDirection.CENTER_VERIFY
            )
        )
        viewModel.onCameraInitialized()
        runCurrent()

        // Frontal vector sent prematurely while TURN_LEFT is active must NOT auto-verify
        val frontalEarly = FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = true,
            noseMustacheLocked = true,
            jawBeardLocked = true,
            headYawDeg = 0f,
            headPitchDeg = 0f,
            signature = OfflineAttendanceStore.DEFAULT_ANSAR_FACE_SIGNATURE
        )
        viewModel.onLiveFaceFrameAnalyzed(frontalEarly)
        viewModel.onLiveFaceFrameAnalyzed(frontalEarly)
        runCurrent()
        val stillScanning = viewModel.terminalPhase.value as TerminalUiPhase.Scanning
        assertEquals(0, stillScanning.currentLivenessStepIndex)

        // Step 1: Turn Left (+14 deg yaw) x2 confirming frames
        val leftFrame = FaceAnalysisFrame(
            faceDetected = true,
            headYawDeg = 14f,
            headPitchDeg = 0f,
            isFrontCamera = true
        )
        viewModel.onLiveFaceFrameAnalyzed(leftFrame)
        viewModel.onLiveFaceFrameAnalyzed(leftFrame)
        runCurrent()
        assertEquals(1, (viewModel.terminalPhase.value as TerminalUiPhase.Scanning).currentLivenessStepIndex)

        // Step 2: Look Up (+11 deg pitch) x2 confirming frames
        val upFrame = FaceAnalysisFrame(
            faceDetected = true,
            headYawDeg = 0f,
            headPitchDeg = 11f,
            isFrontCamera = true
        )
        viewModel.onLiveFaceFrameAnalyzed(upFrame)
        viewModel.onLiveFaceFrameAnalyzed(upFrame)
        runCurrent()
        assertEquals(2, (viewModel.terminalPhase.value as TerminalUiPhase.Scanning).currentLivenessStepIndex)

        // Step 3: Centered biometric match + captured live selfie proceeds directly to Verified
        val ansarFrame = FaceAnalysisFrame(
            faceDetected = true,
            eyesLocked = true,
            noseMustacheLocked = true,
            jawBeardLocked = true,
            signature = OfflineAttendanceStore.DEFAULT_ANSAR_FACE_SIGNATURE,
            selfieImagePath = "/tmp/live_selfie_ansar.jpg"
        )
        viewModel.onLiveFaceFrameAnalyzed(ansarFrame)
        viewModel.onLiveFaceFrameAnalyzed(ansarFrame)
        runCurrent()

        val verified = viewModel.terminalPhase.value
        assertTrue(verified is TerminalUiPhase.Verified)
        verified as TerminalUiPhase.Verified
        assertEquals(OfflineAttendanceStore.MUHAMMAD_ANSAR_ID, verified.employee.id)
        assertEquals(VerificationMethod.FACE_SCAN_1, verified.verificationMethod)
        assertEquals("/tmp/live_selfie_ansar.jpg", verified.liveSelfiePath)

        viewModel.resetToReady()
        runCurrent()
    }

    @Test
    fun sendEmailAndFinish_launchesDeviceAccountPdfIntentAndNavigatesToReadyDashboard() = runTest {
        val reporter = AttendanceEmailReporter(
            context = context,
            appPasswordProvider = { "MY_GMAIL_APP_PASSWORD" }
        )
        val store = OfflineAttendanceStore(context)
        val viewModel = AttendanceViewModel(
            offlineStore = store,
            emailReporter = reporter,
            autoInitBackgroundJobs = false
        )
        runCurrent()

        viewModel.startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
        viewModel.onCameraInitialized()
        runCurrent()

        val selfieFile = File(context.cacheDir, "finish_flow_selfie.jpg")
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        FileOutputStream(selfieFile).use { stream ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        }
        bmp.recycle()

        try {
            val ansarFrame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                noseMustacheLocked = true,
                jawBeardLocked = true,
                signature = OfflineAttendanceStore.DEFAULT_ANSAR_FACE_SIGNATURE,
                selfieImagePath = selfieFile.absolutePath
            )
            viewModel.onLiveFaceFrameAnalyzed(ansarFrame)
            viewModel.onLiveFaceFrameAnalyzed(ansarFrame)
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
            // Must automatically return back to terminal-ready Scanning mode
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)
        } finally {
            selfieFile.delete()
        }
    }

    @Test
    fun checkOut_staysVisibleUntilButtonClickedThenSendsIndividualPdfAndLivePhotoToNich() = runTest(testDispatcher) {
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
                // Server closed cleanly at end of test
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val selfieFile = File(context.cacheDir, "checkout_live_selfie.jpg")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        FileOutputStream(selfieFile).use { stream ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        }
        bmp.recycle()

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

            val ansarFrame = FaceAnalysisFrame(
                faceDetected = true,
                eyesLocked = true,
                noseMustacheLocked = true,
                jawBeardLocked = true,
                signature = OfflineAttendanceStore.DEFAULT_ANSAR_FACE_SIGNATURE,
                selfieImagePath = selfieFile.absolutePath
            )
            viewModel.onLiveFaceFrameAnalyzed(ansarFrame)
            runCurrent()

            // Immediately enters Verified(CHECK_OUT) and does NOT send before employee clicks
            val verified = viewModel.terminalPhase.value as TerminalUiPhase.Verified
            assertEquals(AttendanceEventType.CHECK_OUT, verified.eventType)
            assertEquals(0, dispatchCount.get())
            assertNotNull(verified.liveSelfiePath)
            assertTrue(File(verified.liveSelfiePath!!).exists())

            // Must NOT auto-reset on Check-Out while waiting for user click
            advanceTimeBy(5_000L)
            runCurrent()
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Verified)
            assertEquals(0, dispatchCount.get())

            // Click Send Email & Finish -> sends PDF + live JPEG photo to Nichemployeeattendance@gmail.com and returns to Scanning
            viewModel.sendEmailAndFinish { }
            runCurrent()

            val deadlineMs = System.currentTimeMillis() + 5_000L
            while ((dispatchCount.get() < 1 || viewModel.terminalPhase.value !is TerminalUiPhase.Scanning) &&
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
            assertTrue(viewModel.terminalPhase.value is TerminalUiPhase.Scanning)
        } finally {
            runCatching { serverSocket.close() }
            selfieFile.delete()
        }
    }

    @Test
    fun terminalUi_checkInHidesEmailButtonAndCheckOutShowsSendEmailButton() {
        val ansar = OfflineAttendanceStore.defaultPreRegisteredEmployees().first()

        // 1. Check-In phase: NO email button
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

        // 2. Check-Out phase: Email button immediately displayed and clickable
        phaseState.value = checkInPhase.copy(
            eventType = AttendanceEventType.CHECK_OUT,
            timestampLabel = "05:30:00 PM"
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send_checkout_email_button").assertIsDisplayed().performClick()
        assertEquals(1, checkOutEmailClicks)
    }
}
