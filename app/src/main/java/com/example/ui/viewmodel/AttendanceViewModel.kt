package com.example.ui.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        val emailReportStatus: EmailReportStatus = EmailReportStatus.DISPATCHING
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
    autoInitBackgroundJobs: Boolean = true
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

    private val _terminalPhase = MutableStateFlow<TerminalUiPhase>(TerminalUiPhase.Ready)
    val terminalPhase: StateFlow<TerminalUiPhase> = _terminalPhase.asStateFlow()

    private val _clock = MutableStateFlow(ClockSnapshot())
    val clock: StateFlow<ClockSnapshot> = _clock.asStateFlow()

    private var scanTimerJob: Job? = null
    private var resetTimerJob: Job? = null
    private var emailDispatchJob: Job? = null
    private var scanMatchInFlight = false
    private var consecutiveMatchCount = 0
    private var livenessHoldCount = 0
    private var lastMatchedEmployeeId: String? = null
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
                    val now = Calendar.getInstance()
                    val hour = now.get(Calendar.HOUR_OF_DAY)
                    _clock.value = ClockSnapshot(
                        timeLabel = AttendanceTimeRules.formatTimeLabel(now),
                        dateKey = AttendanceTimeRules.formatDateKey(now),
                        eventType = AttendanceTimeRules.determineEventType(hour),
                        windowLabel = AttendanceTimeRules.windowDescription(hour)
                    )
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
                recentDailyLogs = recentLogs
            )
            if (outcome == EmailReportStatus.SENT || outcome == EmailReportStatus.NEEDS_CONFIG) {
                offlineStore.markEmailSent(logId)
            }
        }
    }

    /**
     * Opens the live CameraX/ML Kit biometric scanner upon tapping VIP Attendance
     * with a dynamic directional liveness sequence before frontal biometric verification.
     */
    fun startVipBiometricScan() {
        startVipBiometricScan(LivenessDirection.buildChallengeSequence())
    }

    fun startVipBiometricScan(livenessSequence: List<LivenessDirection>) {
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
            statusHeadline = "INITIALIZING CAMERA & LIVENESS ENGINE...",
            cameraReady = false,
            livenessSteps = normalizedSequence,
            currentLivenessStepIndex = 0
        )
    }

    /**
     * Begins analysis timing only after CameraX hardware binding succeeds cleanly.
     * On explicit full-scan rejection or timeout, transitions cleanly to Retry Failure (no secondary passcode).
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
            return
        }

        if (lastMatchedEmployeeId == match.employee.id) {
            consecutiveMatchCount++
        } else {
            lastMatchedEmployeeId = match.employee.id
            consecutiveMatchCount = 1
        }

        // Instant match at >= 1% confidence without secondary passcode or waiting loops
        if (consecutiveMatchCount >= REQUIRED_CONFIRMING_FRAMES ||
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
        val now = Calendar.getInstance()
        val eventType = explicitEventType ?: AttendanceTimeRules.currentEventType(now)
        val timeLabel = AttendanceTimeRules.formatTimeLabel(now)
        val dateKey = AttendanceTimeRules.formatDateKey(now)

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

        val senderAccount = emailReporter?.resolveSenderAccountEmail()

        _terminalPhase.value = TerminalUiPhase.Verified(
            employee = employee,
            eventType = eventType,
            verificationMethod = method,
            confidenceScore = confidence,
            timestampLabel = timeLabel,
            syncedToCloud = false,
            logId = localEntry.id,
            liveSelfiePath = liveSelfiePath,
            deviceSenderAccount = senderAccount,
            emailReportStatus = if (emailReporter != null) {
                EmailReportStatus.DISPATCHING
            } else {
                EmailReportStatus.NEEDS_CONFIG
            }
        )

        // Sync to Firebase Firestore in background without blocking the verified screen
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

        launchEmailReportDispatch(
            employee = employee,
            log = localEntry,
            eventType = eventType,
            verificationMethod = method,
            confidence = confidence,
            syncedToCloud = false,
            liveSelfiePath = liveSelfiePath
        )

        scheduleAutoResetToReady()
    }

    /**
     * Dispatches the attendance PDF report using the device's logged-in Google/Gmail account
     * (via background SMTP when configured, or via pre-filled FileProvider Gmail Intent otherwise)
     * and immediately returns the employee to the Ready main dashboard.
     */
    fun sendEmailAndFinish(onLaunchEmailIntent: (Intent) -> Unit) {
        val current = _terminalPhase.value as? TerminalUiPhase.Verified ?: run {
            resetToReady()
            return
        }

        resetTimerJob?.cancel()

        val reporter = emailReporter
        if (reporter == null) {
            resetToReady()
            return
        }

        val matchingLog = buildLogForVerifiedPhase(current)
        val snapshotLogs = offlineStore.logs.value

        // If already sent silently via background SMTP, navigate cleanly back to Ready dashboard
        if (current.emailReportStatus == EmailReportStatus.SENT) {
            resetToReady()
            return
        }

        viewModelScope.launch {
            if (reporter.hasConfiguredSmtpCredentials()) {
                launchEmailReportDispatch(
                    employee = current.employee,
                    log = matchingLog,
                    eventType = current.eventType,
                    verificationMethod = current.verificationMethod,
                    confidence = current.confidenceScore,
                    syncedToCloud = current.syncedToCloud,
                    liveSelfiePath = current.liveSelfiePath
                )
            } else {
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
            resetToReady()
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
        _terminalPhase.value = TerminalUiPhase.Ready
    }

    override fun onCleared() {
        super.onCleared()
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
    }

    private companion object {
        const val TAG = "AttendanceVM"
        const val ATTEMPT_DURATION_MS = 7000L
        const val VERIFIED_AUTO_RESET_MS = 8500L
        const val REQUIRED_CONFIRMING_FRAMES = 1
        const val LIVENESS_HOLD_FRAMES = 1
    }
}
