package com.example.ui.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalRegistrationInput
import com.example.data.local.OfflineAttendanceStore
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.AttendanceTimeRules
import com.example.data.model.Employee
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.FaceBiometricMatcher
import com.example.data.model.LivenessDirection
import com.example.data.model.LivenessEvaluator
import com.example.data.model.VerificationMethod
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.data.repository.AttendanceRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface TerminalUiPhase {
    data object Ready : TerminalUiPhase

    data class Scanning(
        val attempt: Int,
        val statusHeadline: String,
        val cameraReady: Boolean = false,
        val faceDetected: Boolean = false,
        val eyesLocked: Boolean = false,
        val noseMustacheLocked: Boolean = false,
        val jawBeardLocked: Boolean = false,
        val livenessSteps: List<LivenessDirection> = listOf(
            LivenessDirection.TURN_LEFT,
            LivenessDirection.TURN_RIGHT,
            LivenessDirection.CENTER_VERIFY
        ),
        val currentLivenessStepIndex: Int = 0,
        val qualityHint: String? = null
    ) : TerminalUiPhase

    data class Verified(
        val employee: Employee,
        val eventType: AttendanceEventType,
        val verificationMethod: VerificationMethod,
        val confidenceScore: Int,
        val timestampLabel: String,
        val syncedToCloud: Boolean,
        val logId: String = "",
        val liveSelfiePath: String? = null,
        val deviceSenderAccount: String? = null,
        val emailReportStatus: EmailReportStatus = EmailReportStatus.NEEDS_CONFIG
    ) : TerminalUiPhase

    data class Failure(
        val title: String,
        val message: String
    ) : TerminalUiPhase
}

sealed interface DataUiState<out T> {
    data object Loading : DataUiState<Nothing>
    data class Success<T>(val data: T) : DataUiState<T>
    data class Error(val message: String) : DataUiState<Nothing>
}

data class ClockSnapshot(
    val timeLabel: String = AttendanceTimeRules.formatTimeLabel(),
    val dateKey: String = AttendanceTimeRules.formatDateKey(),
    val eventType: AttendanceEventType = AttendanceTimeRules.currentEventType(),
    val windowLabel: String = AttendanceTimeRules.windowDescription(
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    )
)

class AttendanceViewModel(
    private val offlineStore: OfflineAttendanceStore,
    private val repository: AttendanceRepository? = null,
    private val currentUserId: String? = null,
    private val emailReporter: AttendanceEmailReporter? = null,
    autoInitBackgroundJobs: Boolean = true,
    private val calendarProvider: () -> Calendar = { Calendar.getInstance() },
    private val continuousKioskMode: Boolean = true,
    private val sameEmployeeCooldownMs: Long = 3_000L
) : ViewModel() {

    val employeesState: StateFlow<DataUiState<List<Employee>>> =
        offlineStore.employees
            .map<List<Employee>, DataUiState<List<Employee>>> { DataUiState.Success(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DataUiState.Success(offlineStore.employees.value)
            )

    val logsState: StateFlow<DataUiState<List<AttendanceLog>>> =
        offlineStore.logs
            .map<List<AttendanceLog>, DataUiState<List<AttendanceLog>>> { DataUiState.Success(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DataUiState.Success(offlineStore.logs.value)
            )

    val pendingSyncIds: StateFlow<Set<String>> = offlineStore.pendingSyncLogIds

    private val _cloudSyncError = MutableStateFlow<String?>(null)
    val cloudSyncError: StateFlow<String?> = _cloudSyncError.asStateFlow()

    private val _terminalPhase = MutableStateFlow<TerminalUiPhase>(
        if (autoInitBackgroundJobs) {
            TerminalUiPhase.Scanning(
                attempt = 1,
                statusHeadline = "TERMINAL SCANNER ACTIVE",
                cameraReady = false,
                livenessSteps = listOf(LivenessDirection.CENTER_VERIFY),
                currentLivenessStepIndex = 0
            )
        } else {
            TerminalUiPhase.Ready
        }
    )
    val terminalPhase: StateFlow<TerminalUiPhase> = _terminalPhase.asStateFlow()

    private val _showRegistrationScreen = MutableStateFlow(
        autoInitBackgroundJobs && !offlineStore.hasRegisteredFaceProfiles()
    )
    val showRegistrationScreen: StateFlow<Boolean> = _showRegistrationScreen.asStateFlow()

    private val _clock = MutableStateFlow(buildClockSnapshot(calendarProvider()))
    val clock: StateFlow<ClockSnapshot> = _clock.asStateFlow()

    private fun buildClockSnapshot(now: Calendar): ClockSnapshot {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return ClockSnapshot(
            timeLabel = AttendanceTimeRules.formatTimeLabel(now),
            dateKey = AttendanceTimeRules.formatDateKey(now),
            eventType = AttendanceTimeRules.determineEventType(hour),
            windowLabel = AttendanceTimeRules.windowDescription(hour)
        )
    }

    private var scanTimerJob: Job? = null
    private var resetTimerJob: Job? = null
    private var emailDispatchJob: Job? = null
    private var scanMatchInFlight = false
    private var consecutiveMatchCount = 0
    private var livenessHoldCount = 0
    private var lastMatchedEmployeeId: String? = null
    private var lastVerifiedEmployeeId: String? = null
    private var lastVerifiedTimestampMs: Long = 0L
    private var latestCapturedSelfiePath: String? = null
    private var analyzedFaceFrames = 0
    private var rejectedFaceFrames = 0
    private var highestRejectedConfidence = 0

    init {
        if (autoInitBackgroundJobs) {
            viewModelScope.launch {
                offlineStore.calibrateRegisteredReferenceFaces()
            }

            if (repository != null && !currentUserId.isNullOrBlank()) {
                startAuthenticatedCloudSync(repository, currentUserId)
            } else {
                viewModelScope.launch {
                    retryPendingEmailReports()
                }
            }

            viewModelScope.launch {
                while (isActive) {
                    _clock.value = buildClockSnapshot(calendarProvider())
                    delay(1000L)
                }
            }
        }
    }

    private fun startAuthenticatedCloudSync(repo: AttendanceRepository, uid: String) {
        viewModelScope.launch {
            repo.ensureStarterDirectoryIfEmpty(offlineStore.employees.value)
                .onFailure { err ->
                    _cloudSyncError.value = err.localizedMessage
                }
            syncPendingChangesInternal(repo)
        }

        viewModelScope.launch {
            repo.observeEmployees(uid)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val root = firestoreError ?: error
                    Log.w(TAG, "Cloud employee listener error", root)
                    _cloudSyncError.value = root.localizedMessage
                }
                .collectLatest { remoteEmployees ->
                    _cloudSyncError.value = null
                    offlineStore.mergeRemoteEmployees(remoteEmployees)
                }
        }

        viewModelScope.launch {
            repo.observeAttendanceLogs(uid)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val root = firestoreError ?: error
                    Log.w(TAG, "Cloud log listener error", root)
                    _cloudSyncError.value = root.localizedMessage
                }
                .collectLatest { remoteLogs ->
                    _cloudSyncError.value = null
                    offlineStore.mergeRemoteLogs(remoteLogs)
                }
        }
    }

    private suspend fun syncPendingChangesInternal(repo: AttendanceRepository) {
        val pendingDeletes = offlineStore.pendingDeleteEmployeeIds.value.toList()
        for (empId in pendingDeletes) {
            repo.deleteEmployee(empId)
                .onSuccess { offlineStore.markEmployeeDeleteSynced(empId) }
                .onFailure { err -> _cloudSyncError.value = err.localizedMessage }
        }

        val employeesById = offlineStore.employees.value.associateBy { it.id }
        val pendingEmployees = offlineStore.pendingSyncEmployeeIds.value.toList()
        for (empId in pendingEmployees) {
            val emp = employeesById[empId]
            if (emp == null) {
                offlineStore.markEmployeeSynced(empId)
                continue
            }
            repo.upsertEmployee(emp, overwriteIfExists = true)
                .onSuccess { offlineStore.markEmployeeSynced(empId) }
                .onFailure { err -> _cloudSyncError.value = err.localizedMessage }
        }

        val pendingIds = offlineStore.pendingSyncLogIds.value
        if (pendingIds.isEmpty()) {
            retryPendingEmailReports()
            return
        }

        val allEmployees = offlineStore.employees.value.associateBy { it.id }
        val pendingLogs = offlineStore.logs.value.filter { it.id in pendingIds }.reversed()
        for (log in pendingLogs) {
            val emp = allEmployees[log.employeeId] ?: Employee(
                id = log.employeeId,
                employeeCode = log.employeeCode,
                name = log.employeeName,
                department = log.department,
                roleTitle = "Authorized Staff",
                passcode = "147",
                avatarKey = log.avatarKey,
                isActive = true
            )
            repo.upsertEmployee(emp, overwriteIfExists = false)
            val res = repo.logAttendance(
                employee = emp,
                eventType = AttendanceEventType.fromWire(log.eventType),
                verificationMethod = VerificationMethod.fromWire(log.verificationMethod),
                confidenceScore = log.confidenceScore.toInt(),
                loggedTimeLabel = log.loggedTimeLabel,
                loggedDateKey = log.loggedDateKey,
                explicitLogId = log.id
            )
            if (res.isSuccess) {
                offlineStore.markLogSynced(log.id)
            } else {
                _cloudSyncError.value = res.exceptionOrNull()?.localizedMessage
            }
        }
        retryPendingEmailReports()
    }

    private suspend fun retryPendingEmailReports() {
        val reporter = emailReporter ?: return
        val pendingEmails = offlineStore.pendingEmailLogIds.value.toList()
        if (pendingEmails.isEmpty()) return

        val employeesById = offlineStore.employees.value.associateBy { it.id }
        val logsById = offlineStore.logs.value.associateBy { it.id }
        val selfieMap = offlineStore.getSelfiePathsByLogId()
        val recentLogs = offlineStore.logs.value
        val pendingCloudIds = offlineStore.pendingSyncLogIds.value

        for (logId in pendingEmails) {
            val log = logsById[logId]
            if (log == null) {
                offlineStore.markEmailSent(logId)
                continue
            }
            val employee = employeesById[log.employeeId] ?: Employee(
                id = log.employeeId,
                employeeCode = log.employeeCode,
                name = log.employeeName,
                department = log.department,
                roleTitle = "Authorized Staff",
                avatarKey = log.avatarKey
            )
            val outcome = reporter.generateAndSendReport(
                employee = employee,
                log = log,
                eventType = AttendanceEventType.fromWire(log.eventType),
                verificationMethod = VerificationMethod.fromWire(log.verificationMethod),
                confidenceScore = log.confidenceScore.toInt(),
                syncedToCloud = log.id !in pendingCloudIds,
                recentDailyLogs = recentLogs,
                liveSelfiePath = selfieMap[log.id]
            )
            if (outcome == EmailReportStatus.SENT || outcome == EmailReportStatus.NEEDS_CONFIG) {
                offlineStore.markEmailSent(logId)
            }
        }
    }

    fun openRegistrationScreen() {
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        scanMatchInFlight = false
        consecutiveMatchCount = 0
        lastMatchedEmployeeId = null
        _showRegistrationScreen.value = true
    }

    fun closeRegistrationScreen() {
        _showRegistrationScreen.value = false
        startVipBiometricScan(
            livenessSequence = listOf(LivenessDirection.CENTER_VERIFY),
            preserveCameraReady = false
        )
    }

    fun registerEmployeesBatch(inputs: List<LocalRegistrationInput>) {
        if (inputs.isEmpty()) return
        val registered = offlineStore.registerEmployeesBatch(inputs)
        val repo = repository
        if (repo != null && !currentUserId.isNullOrBlank() && registered.isNotEmpty()) {
            viewModelScope.launch {
                registered.forEach { emp ->
                    repo.upsertEmployee(emp, overwriteIfExists = true)
                        .onSuccess { offlineStore.markEmployeeSynced(emp.id) }
                }
            }
        }
        closeRegistrationScreen()
    }

    fun deleteRegisteredEmployee(employeeId: String) {
        offlineStore.deleteEmployee(employeeId, queueCloudDelete = true)
        val repo = repository
        if (repo != null && !currentUserId.isNullOrBlank()) {
            viewModelScope.launch {
                repo.deleteEmployee(employeeId)
                    .onSuccess { offlineStore.markEmployeeDeleteSynced(employeeId) }
            }
        }
    }

    /**
     * Launches continuous hands-free biometric face scanning with 90%+ accuracy verification.
     */
    fun startVipBiometricScan() {
        startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
    }

    fun startVipBiometricScan(
        livenessSequence: List<LivenessDirection>,
        preserveCameraReady: Boolean = false
    ) {
        val normalizedSequence = if (livenessSequence.isEmpty()) {
            listOf(LivenessDirection.CENTER_VERIFY)
        } else {
            livenessSequence
        }
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        scanMatchInFlight = false
        consecutiveMatchCount = 0
        livenessHoldCount = 0
        lastMatchedEmployeeId = null
        latestCapturedSelfiePath = null
        analyzedFaceFrames = 0
        rejectedFaceFrames = 0
        highestRejectedConfidence = 0

        _terminalPhase.value = TerminalUiPhase.Scanning(
            attempt = 1,
            statusHeadline = "TERMINAL SCANNER ACTIVE",
            cameraReady = preserveCameraReady,
            livenessSteps = normalizedSequence,
            currentLivenessStepIndex = 0
        )
    }

    /**
     * Begins analysis timing only after CameraX hardware binding succeeds cleanly.
     * In continuous kiosk mode, stays ready inside Scanning standby indefinitely without blocking UI.
     */
    fun onCameraInitialized() {
        val current = _terminalPhase.value as? TerminalUiPhase.Scanning ?: return
        if (current.cameraReady) return

        val firstInstruction = current.livenessSteps
            .getOrElse(current.currentLivenessStepIndex) { LivenessDirection.CENTER_VERIFY }
            .instruction

        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Scanning) {
                phase.copy(
                    cameraReady = true,
                    statusHeadline = "LIVENESS 1/${phase.livenessSteps.size} • $firstInstruction"
                )
            } else {
                phase
            }
        }

        if (continuousKioskMode) return

        scanTimerJob?.cancel()
        scanTimerJob = viewModelScope.launch {
            delay(ATTEMPT_DURATION_MS)
            if (_terminalPhase.value is TerminalUiPhase.Scanning && !scanMatchInFlight) {
                _terminalPhase.update { phase ->
                    if (phase is TerminalUiPhase.Scanning) {
                        val activeStep = phase.livenessSteps
                            .getOrElse(phase.currentLivenessStepIndex) { LivenessDirection.CENTER_VERIFY }
                        phase.copy(
                            attempt = 2,
                            statusHeadline = "AUTO-RETRY (2/2) • ${activeStep.instruction}"
                        )
                    } else {
                        phase
                    }
                }
                delay(ATTEMPT_DURATION_MS)
                if (_terminalPhase.value is TerminalUiPhase.Scanning && !scanMatchInFlight) {
                    val activePhase = _terminalPhase.value as? TerminalUiPhase.Scanning
                    val livenessIncomplete = activePhase != null &&
                        activePhase.currentLivenessStepIndex < activePhase.livenessSteps.lastIndex
                    val failureMessage = when {
                        livenessIncomplete ->
                            "Directional liveness sequence was not completed in time. Please follow the on-screen arrows and try again."
                        rejectedFaceFrames > 0 ->
                            "Facial geometry did not match any enrolled staff member (${highestRejectedConfidence}% match). Ensure good lighting and retry."
                        analyzedFaceFrames > 0 ->
                            "Could not lock eyes, nose, and jawline cleanly. Look straight into the camera and retry."
                        else ->
                            "No live face detected inside the scanner reticle. Position your face clearly and retry."
                    }
                    _terminalPhase.value = TerminalUiPhase.Failure(
                        title = "Face Verification Incomplete",
                        message = failureMessage
                    )
                }
            }
        }
    }

    fun onCameraInitFailed(reason: String) {
        val current = _terminalPhase.value as? TerminalUiPhase.Scanning ?: return
        if (current.cameraReady || scanMatchInFlight) return
        scanTimerJob?.cancel()
        _terminalPhase.value = TerminalUiPhase.Failure(
            title = "Camera Hardware Unavailable",
            message = "Could not initialize camera sensor ($reason). Check permissions and retry."
        )
    }

    fun onLiveFaceFrameAnalyzed(frame: FaceAnalysisFrame) {
        val current = _terminalPhase.value as? TerminalUiPhase.Scanning ?: return

        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Scanning) {
                phase.copy(
                    faceDetected = frame.faceDetected,
                    eyesLocked = frame.eyesLocked,
                    noseMustacheLocked = frame.noseMustacheLocked,
                    jawBeardLocked = frame.jawBeardLocked,
                    qualityHint = frame.qualityIssue
                )
            } else {
                phase
            }
        }

        if (!frame.selfieImagePath.isNullOrBlank()) {
            latestCapturedSelfiePath = frame.selfieImagePath
        }

        if (!frame.faceDetected || scanMatchInFlight) return

        val steps = current.livenessSteps
        val stepIndex = current.currentLivenessStepIndex.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
        val activeDirection = steps.getOrElse(stepIndex) { LivenessDirection.CENTER_VERIFY }

        // Evaluate directional liveness step before advancing to frontal biometric match & selfie capture
        if (activeDirection != LivenessDirection.CENTER_VERIFY) {
            val directionPassed = LivenessEvaluator.isDirectionSatisfied(
                direction = activeDirection,
                headYawDeg = frame.headYawDeg,
                headPitchDeg = frame.headPitchDeg,
                isFrontCamera = frame.isFrontCamera
            )
            if (directionPassed) {
                livenessHoldCount++
                if (livenessHoldCount >= LIVENESS_HOLD_FRAMES) {
                    livenessHoldCount = 0
                    val nextIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex)
                    val nextDirection = steps[nextIndex]
                    _terminalPhase.update { phase ->
                        if (phase is TerminalUiPhase.Scanning) {
                            phase.copy(
                                currentLivenessStepIndex = nextIndex,
                                statusHeadline = "LIVENESS ${nextIndex + 1}/${steps.size} • ${nextDirection.instruction}",
                                qualityHint = null
                            )
                        } else {
                            phase
                        }
                    }
                }
            } else {
                livenessHoldCount = 0
            }
            return
        }

        // Require clean frontal alignment & locked eye landmarks for accurate recognition
        if (!frame.headPoseAligned || !frame.eyesLocked) {
            consecutiveMatchCount = 0
            lastMatchedEmployeeId = null
            _terminalPhase.update { phase ->
                if (phase is TerminalUiPhase.Scanning) {
                    phase.copy(qualityHint = frame.qualityIssue ?: "Look straight into the camera")
                } else {
                    phase
                }
            }
            return
        }

        val liveSignature = frame.signature
        if (liveSignature.isNullOrBlank()) {
            consecutiveMatchCount = 0
            return
        }

        analyzedFaceFrames++
        val employees = offlineStore.employees.value
        val evaluation = FaceBiometricMatcher.evaluateFrame(liveSignature, employees)
        val match = evaluation.match

        if (match == null) {
            consecutiveMatchCount = 0
            lastMatchedEmployeeId = null
            rejectedFaceFrames++
            if (evaluation.bestConfidencePercent > highestRejectedConfidence) {
                highestRejectedConfidence = evaluation.bestConfidencePercent
            }
            _terminalPhase.update { phase ->
                if (phase is TerminalUiPhase.Scanning) {
                    val hint = if (employees.none { FaceBiometricMatcher.parseSignature(it.faceSignature) != null }) {
                        "No registered faces in local storage • Tap Register"
                    } else {
                        "Unrecognized face (${evaluation.bestConfidencePercent}% • need 90%+)"
                    }
                    phase.copy(qualityHint = hint)
                } else {
                    phase
                }
            }
            return
        }

        val nowMs = System.currentTimeMillis()
        if (sameEmployeeCooldownMs > 0L &&
            match.employee.id == lastVerifiedEmployeeId &&
            (nowMs - lastVerifiedTimestampMs) < sameEmployeeCooldownMs
        ) {
            return
        }

        if (lastMatchedEmployeeId == match.employee.id) {
            consecutiveMatchCount++
        } else {
            lastMatchedEmployeeId = match.employee.id
            consecutiveMatchCount = 1
        }

        // High-accuracy lock: require >= 90% match confidence across confirming frames
        if (consecutiveMatchCount >= REQUIRED_CONFIRMING_FRAMES &&
            match.confidencePercent >= FaceBiometricMatcher.MATCH_THRESHOLD_PERCENT
        ) {
            scanMatchInFlight = true
            scanTimerJob?.cancel()
            val method = if (current.attempt == 1) {
                VerificationMethod.FACE_SCAN_1
            } else {
                VerificationMethod.FACE_SCAN_2
            }
            val capturedSelfie = frame.selfieImagePath ?: latestCapturedSelfiePath

            viewModelScope.launch {
                commitVerifiedAttendance(
                    employee = match.employee,
                    method = method,
                    confidence = match.confidencePercent,
                    explicitEventType = null,
                    liveSelfiePath = capturedSelfie
                )
            }
        }
    }

    private suspend fun commitVerifiedAttendance(
        employee: Employee,
        method: VerificationMethod,
        confidence: Int,
        explicitEventType: AttendanceEventType?,
        liveSelfiePath: String? = null
    ) {
        val now = calendarProvider()
        val eventType = explicitEventType ?: AttendanceTimeRules.currentEventType(now)
        val timeLabel = AttendanceTimeRules.formatTimeLabel(now)
        val dateKey = AttendanceTimeRules.formatDateKey(now)
        lastVerifiedEmployeeId = employee.id
        lastVerifiedTimestampMs = System.currentTimeMillis()

        // Persist immediately to local storage so check-in/out never freezes the UI
        val localEntry = offlineStore.recordLocalAttendance(
            employee = employee,
            eventType = eventType,
            verificationMethod = method,
            confidenceScore = confidence,
            loggedTimeLabel = timeLabel,
            loggedDateKey = dateKey,
            synced = false
        )
        val durableSelfiePath = offlineStore.persistSelfieForLog(localEntry.id, liveSelfiePath)
            ?: liveSelfiePath

        val senderAccount = emailReporter?.resolveSenderAccountEmail()

        _terminalPhase.value = TerminalUiPhase.Verified(
            employee = employee,
            eventType = eventType,
            verificationMethod = method,
            confidenceScore = confidence,
            timestampLabel = timeLabel,
            syncedToCloud = false,
            logId = localEntry.id,
            liveSelfiePath = durableSelfiePath,
            deviceSenderAccount = senderAccount,
            emailReportStatus = EmailReportStatus.NEEDS_CONFIG
        )

        // Sync to Firebase Firestore in background without blocking the verified confirmation
        val repo = repository
        if (repo != null && !currentUserId.isNullOrBlank()) {
            viewModelScope.launch {
                runCatching {
                    repo.upsertEmployee(employee)
                    repo.logAttendance(
                        employee = employee,
                        eventType = eventType,
                        verificationMethod = method,
                        confidenceScore = confidence,
                        loggedTimeLabel = timeLabel,
                        loggedDateKey = dateKey,
                        explicitLogId = localEntry.id
                    )
                }.onSuccess { cloudResult ->
                    if (cloudResult.isSuccess) {
                        offlineStore.markLogSynced(localEntry.id)
                        _terminalPhase.update { phase ->
                            if (phase is TerminalUiPhase.Verified && phase.logId == localEntry.id) {
                                phase.copy(syncedToCloud = true)
                            } else {
                                phase
                            }
                        }
                    } else {
                        _cloudSyncError.value = cloudResult.exceptionOrNull()?.localizedMessage
                    }
                }.onFailure { err ->
                    _cloudSyncError.value = err.localizedMessage
                }
            }
        }

        // Conditional Email Notification:
        // - CHECK-IN: Never dispatch email. Auto-return to live scanning after 2.5s.
        // - CHECK-OUT: Automatically trigger SMTP/TLS email dispatch when Check-Out is recorded.
        if (eventType == AttendanceEventType.CHECK_IN) {
            scheduleAutoResetToReady()
        } else {
            val reporter = emailReporter
            if (reporter != null && reporter.hasConfiguredSmtpCredentials()) {
                _terminalPhase.update { phase ->
                    if (phase is TerminalUiPhase.Verified && phase.logId == localEntry.id) {
                        phase.copy(emailReportStatus = EmailReportStatus.DISPATCHING)
                    } else {
                        phase
                    }
                }
                launchEmailReportDispatch(
                    employee = employee,
                    log = localEntry,
                    eventType = eventType,
                    verificationMethod = method,
                    confidence = confidence,
                    syncedToCloud = false,
                    liveSelfiePath = durableSelfiePath,
                    rescheduleResetAfter = false
                )
            }
        }
    }

    /**
     * Dispatches the individual attendance PDF report and live selfie photo
     * to Nichemployeeattendance@gmail.com when the employee taps Send Email & Finish
     * after a recorded Check-Out, then returns the terminal to active Scanning.
     */
    fun sendEmailAndFinish(onLaunchEmailIntent: (Intent) -> Unit) {
        val current = _terminalPhase.value as? TerminalUiPhase.Verified ?: run {
            resetToReady()
            return
        }
        if (current.emailReportStatus == EmailReportStatus.DISPATCHING) return

        resetTimerJob?.cancel()

        val reporter = emailReporter
        if (reporter == null) {
            resetToReady()
            return
        }

        val matchingLog = buildLogForVerifiedPhase(current)
        val snapshotLogs = offlineStore.logs.value

        if (current.emailReportStatus == EmailReportStatus.SENT) {
            resetToReady()
            return
        }

        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                phase.copy(emailReportStatus = EmailReportStatus.DISPATCHING)
            } else {
                phase
            }
        }

        viewModelScope.launch {
            var dispatchedViaSmtp = false
            if (reporter.hasConfiguredSmtpCredentials()) {
                val outcome = reporter.generateAndSendReport(
                    employee = current.employee,
                    log = matchingLog,
                    eventType = current.eventType,
                    verificationMethod = current.verificationMethod,
                    confidenceScore = current.confidenceScore,
                    syncedToCloud = current.syncedToCloud,
                    recentDailyLogs = snapshotLogs,
                    liveSelfiePath = current.liveSelfiePath
                )
                if (outcome == EmailReportStatus.SENT) {
                    offlineStore.markEmailSent(matchingLog.id)
                    retryPendingEmailReports()
                    dispatchedViaSmtp = true
                }
            }

            if (!dispatchedViaSmtp) {
                runCatching {
                    val pdfFile = reporter.generateReportPdfFile(
                        employee = current.employee,
                        log = matchingLog,
                        eventType = current.eventType,
                        verificationMethod = current.verificationMethod,
                        confidenceScore = current.confidenceScore,
                        syncedToCloud = current.syncedToCloud,
                        recentDailyLogs = snapshotLogs,
                        liveSelfiePath = current.liveSelfiePath
                    )
                    val gmailIntent = reporter.buildDeviceGmailShareIntent(
                        pdfFile = pdfFile,
                        employee = current.employee,
                        log = matchingLog,
                        eventType = current.eventType,
                        verificationMethod = current.verificationMethod,
                        confidenceScore = current.confidenceScore,
                        syncedToCloud = current.syncedToCloud
                    )
                    offlineStore.markEmailSent(matchingLog.id)
                    onLaunchEmailIntent(gmailIntent)
                }.onFailure { err ->
                    Log.w(TAG, "Failed to launch Gmail share intent: ${err.message}")
                }
            }
            resetToReady()
        }
    }

    fun sendVerifiedReportToEmail() {
        val current = _terminalPhase.value as? TerminalUiPhase.Verified ?: return
        if (emailDispatchJob?.isActive == true &&
            current.emailReportStatus == EmailReportStatus.DISPATCHING
        ) {
            return
        }

        resetTimerJob?.cancel()

        val reporter = emailReporter
        if (reporter == null) {
            _terminalPhase.update { phase ->
                if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                    phase.copy(emailReportStatus = EmailReportStatus.NEEDS_CONFIG)
                } else {
                    phase
                }
            }
            scheduleAutoResetToReady()
            return
        }

        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                phase.copy(emailReportStatus = EmailReportStatus.DISPATCHING)
            } else {
                phase
            }
        }

        val matchingLog = buildLogForVerifiedPhase(current)
        launchEmailReportDispatch(
            employee = current.employee,
            log = matchingLog,
            eventType = current.eventType,
            verificationMethod = current.verificationMethod,
            confidence = current.confidenceScore,
            syncedToCloud = current.syncedToCloud,
            liveSelfiePath = current.liveSelfiePath,
            rescheduleResetAfter = true
        )
    }

    private fun buildLogForVerifiedPhase(current: TerminalUiPhase.Verified): AttendanceLog {
        return offlineStore.logs.value.firstOrNull { it.id == current.logId }
            ?: AttendanceLog(
                id = current.logId.ifBlank { "manual_${System.currentTimeMillis()}" },
                employeeId = current.employee.id,
                employeeCode = current.employee.employeeCode,
                employeeName = current.employee.name,
                department = current.employee.department,
                avatarKey = current.employee.avatarKey,
                eventType = current.eventType.wireValue,
                verificationMethod = current.verificationMethod.wireValue,
                confidenceScore = current.confidenceScore.toLong().coerceIn(0L, 100L),
                loggedTimeLabel = current.timestampLabel,
                loggedDateKey = AttendanceTimeRules.formatDateKey()
            )
    }

    private fun launchEmailReportDispatch(
        employee: Employee,
        log: AttendanceLog,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidence: Int,
        syncedToCloud: Boolean,
        liveSelfiePath: String? = null,
        rescheduleResetAfter: Boolean = false
    ) {
        val reporter = emailReporter ?: return
        val snapshotLogs = offlineStore.logs.value
        emailDispatchJob?.cancel()
        emailDispatchJob = viewModelScope.launch {
            val outcome = reporter.generateAndSendReport(
                employee = employee,
                log = log,
                eventType = eventType,
                verificationMethod = verificationMethod,
                confidenceScore = confidence,
                syncedToCloud = syncedToCloud,
                recentDailyLogs = snapshotLogs,
                liveSelfiePath = liveSelfiePath
            )
            if (outcome == EmailReportStatus.QUEUED_OFFLINE) {
                offlineStore.markEmailPending(log.id)
            } else if (outcome == EmailReportStatus.SENT) {
                offlineStore.markEmailSent(log.id)
                retryPendingEmailReports()
            }
            _terminalPhase.update { current ->
                if (current is TerminalUiPhase.Verified && current.logId == log.id) {
                    current.copy(emailReportStatus = outcome)
                } else {
                    current
                }
            }
            if (rescheduleResetAfter) {
                scheduleAutoResetToReady()
            }
        }
    }

    private fun scheduleAutoResetToReady() {
        resetTimerJob?.cancel()
        resetTimerJob = viewModelScope.launch {
            delay(VERIFIED_AUTO_RESET_MS)
            if (continuousKioskMode) {
                startVipBiometricScan(
                    livenessSequence = listOf(LivenessDirection.CENTER_VERIFY),
                    preserveCameraReady = true
                )
            } else {
                resetToReady()
            }
        }
    }

    fun resetToReady() {
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        scanMatchInFlight = false
        consecutiveMatchCount = 0
        livenessHoldCount = 0
        lastMatchedEmployeeId = null
        latestCapturedSelfiePath = null
        _terminalPhase.value = if (continuousKioskMode) {
            TerminalUiPhase.Scanning(
                attempt = 1,
                statusHeadline = "TERMINAL SCANNER ACTIVE",
                cameraReady = true,
                livenessSteps = listOf(LivenessDirection.CENTER_VERIFY),
                currentLivenessStepIndex = 0
            )
        } else {
            TerminalUiPhase.Ready
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
    }

    private companion object {
        const val TAG = "AttendanceVM"
        const val ATTEMPT_DURATION_MS = 7000L
        const val VERIFIED_AUTO_RESET_MS = 2500L
        const val REQUIRED_CONFIRMING_FRAMES = 2
        const val LIVENESS_HOLD_FRAMES = 1
    }
}
