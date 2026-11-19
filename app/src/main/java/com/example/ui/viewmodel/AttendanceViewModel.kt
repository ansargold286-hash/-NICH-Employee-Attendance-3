package com.example.ui.viewmodel

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.LocalRegistrationInput
import com.example.data.local.OfflineAttendanceStore
import com.example.data.local.PendingCheckOutLock
import com.example.data.local.PendingServerEmailReport
import com.example.data.location.LocationAddressProvider
import com.example.data.location.NoOpLocationAddressProvider
import com.example.data.model.AntiSpoofingLivenessTracker
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.AttendanceTimeRules
import com.example.data.model.CnicOcrParser
import com.example.data.model.CnicScanFrame
import com.example.data.model.Employee
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.FaceBiometricMatcher
import com.example.data.model.LivenessDirection
import com.example.data.model.LivenessEvaluator
import com.example.data.model.VerificationMethod
import com.example.data.remote.ServerSyncClient
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.data.repository.AttendanceRepository
import com.example.data.time.DeviceCalendarTimeProvider
import com.example.data.time.NetworkTimeProvider
import com.example.data.time.NetworkTimeSnapshot
import com.example.voice.NoOpVoiceSpeaker
import com.example.voice.VoicePromptScript
import com.example.voice.VoiceSpeaker
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
        val livenessVerified: Boolean = false,
        val spoofSuspected: Boolean = false,
        val livenessSteps: List<LivenessDirection> = listOf(
            LivenessDirection.TURN_LEFT,
            LivenessDirection.TURN_RIGHT,
            LivenessDirection.CENTER_VERIFY
        ),
        val currentLivenessStepIndex: Int = 0,
        val qualityHint: String? = null
    ) : TerminalUiPhase

    data class CnicScanning(
        val employee: Employee,
        val faceConfidenceScore: Int,
        val verificationMethod: VerificationMethod,
        val liveSelfiePath: String? = null,
        val cameraReady: Boolean = false,
        val cardDetected: Boolean = false,
        val insideGreenFrame: Boolean = false,
        val detectedCnicName: String = "",
        val detectedCnicNumber: String = "",
        val statusHeadline: String = "STEP B • HOLD CNIC FRONT INSIDE GREEN FRAME",
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
        val gpsCoordinates: String = "",
        val locationAddress: String = "",
        val timeSourceLabel: String = "",
        val pdfReportPath: String = "",
        val isPersistentLock: Boolean = false,
        val deviceSenderAccount: String? = null,
        val emailReportStatus: EmailReportStatus = EmailReportStatus.NEEDS_CONFIG,
        val emailEnforcementWarningActive: Boolean = false,
        val emailEnforcementMessage: String? = null,
        val checkOutEmailTriggeredOrSent: Boolean = false,
        val matchedCnicName: String = "",
        val matchedCnicNumber: String = "",
        val cnicPhotoPath: String? = null,
        val isBatchMode: Boolean = false,
        val batchCompletedCount: Int = 1,
        val batchTotalCount: Int = 1,
        val batchAllCompleted: Boolean = true,
        val batchPendingEmployeeNames: List<String> = emptyList(),
        val batchDateKey: String = "",
        val emailAutoUnlockSecondsRemaining: Int? = null
    ) : TerminalUiPhase {
        val isCheckOutEmailCompleted: Boolean
            get() = eventType != AttendanceEventType.CHECK_OUT ||
                (isBatchMode && !batchAllCompleted) ||
                checkOutEmailTriggeredOrSent ||
                emailReportStatus == EmailReportStatus.SENT
    }

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
    private val sameEmployeeCooldownMs: Long = 3_000L,
    private val voiceSpeaker: VoiceSpeaker = NoOpVoiceSpeaker,
    private val networkTimeProvider: NetworkTimeProvider? = null,
    private val locationProvider: LocationAddressProvider = NoOpLocationAddressProvider,
    private val serverSyncClient: ServerSyncClient? = null
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
        autoInitBackgroundJobs &&
            !offlineStore.isCheckOutEmailLocked &&
            !offlineStore.hasRegisteredFaceProfiles()
    )
    val showRegistrationScreen: StateFlow<Boolean> = _showRegistrationScreen.asStateFlow()

    private val activeTimeProvider: NetworkTimeProvider =
        networkTimeProvider ?: DeviceCalendarTimeProvider(calendarProvider)

    private val _networkTimeStatus = MutableStateFlow(activeTimeProvider.currentSnapshot())
    val networkTimeStatus: StateFlow<NetworkTimeSnapshot> = _networkTimeStatus.asStateFlow()

    private val _clock = MutableStateFlow(buildClockSnapshot(activeTimeProvider.currentCalendar()))
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
    private var emailReminderJob: Job? = null
    private var emailCountdownJob: Job? = null
    private var pdfPreGenJob: Job? = null
    private var scanMatchInFlight = false
    private var cnicCommitInFlight = false
    private var consecutiveMatchCount = 0
    private var livenessHoldCount = 0
    private var lastMatchedEmployeeId: String? = null
    private var lastVerifiedEmployeeId: String? = null
    private var lastVerifiedTimestampMs: Long = 0L
    private var latestCapturedSelfiePath: String? = null
    private var analyzedFaceFrames = 0
    private var rejectedFaceFrames = 0
    private var highestRejectedConfidence = 0
    private var lastVoicePromptKey: String? = null
    private var lastVoicePromptAtMs: Long = 0L
    private var welcomeSpokenAtMs: Long = 0L
    private val antiSpoofingTracker = AntiSpoofingLivenessTracker()

    init {
        val persistedLock = offlineStore.pendingCheckOutLock.value
        if (persistedLock != null) {
            restorePhaseFromPendingLock(
                lock = persistedLock,
                triggerVoice = autoInitBackgroundJobs
            )
        }

        if (autoInitBackgroundJobs) {
            viewModelScope.launch {
                offlineStore.calibrateRegisteredReferenceFaces()
            }

            viewModelScope.launch {
                while (isActive) {
                    val synced = runCatching { activeTimeProvider.syncNow() }.getOrNull()
                    if (synced != null) {
                        _networkTimeStatus.value = synced
                        if (synced.tamperingDetected) {
                            speakScanGuidance(
                                key = "clock_tamper",
                                message = VoicePromptScript.clockTamperDetectedPrompt(),
                                minIntervalMs = 60_000L
                            )
                        }
                    }
                    delay(5 * 60 * 1000L)
                }
            }

            if (repository != null && !currentUserId.isNullOrBlank()) {
                startAuthenticatedCloudSync(repository, currentUserId)
            } else {
                viewModelScope.launch {
                    retryPendingEmailReports()
                }
            }

            triggerServerSyncAsync()

            viewModelScope.launch {
                while (isActive) {
                    _networkTimeStatus.value = activeTimeProvider.currentSnapshot()
                    _clock.value = buildClockSnapshot(activeTimeProvider.currentCalendar())
                    delay(1000L)
                }
            }
        }
    }

    private fun restorePhaseFromPendingLock(
        lock: PendingCheckOutLock,
        triggerVoice: Boolean,
        throttleVoice: Boolean = false
    ) {
        if (emailCountdownJob?.isActive == true) return
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        scanMatchInFlight = false
        cnicCommitInFlight = false
        _showRegistrationScreen.value = false

        val matchedEmployee = offlineStore.employees.value.firstOrNull { it.id == lock.employeeId }
            ?: Employee(
                id = lock.employeeId,
                employeeCode = lock.employeeCode,
                name = lock.employeeName,
                department = lock.department,
                roleTitle = lock.roleTitle,
                avatarKey = lock.avatarKey,
                localPhotoPath = lock.localEmployeePhotoPath
            )

        val currentStatus = if (emailReporter?.hasConfiguredSmtpCredentials() == true) {
            EmailReportStatus.QUEUED_OFFLINE
        } else {
            EmailReportStatus.NEEDS_CONFIG
        }

        val batchCount = lock.batchEmployeeCount.coerceAtLeast(1)
        val batchDate = lock.batchDateKey.ifBlank { lock.loggedDateKey }

        val restoredCnicName = CnicOcrParser.resolveVerifiedDisplayName(
            observedName = lock.matchedCnicName,
            employee = matchedEmployee
        )
        val restoredCnicPhotoPath = lock.cnicPhotoPath
            .ifBlank { matchedEmployee.cnicPhotoPath }
            .ifBlank { null }

        _terminalPhase.value = TerminalUiPhase.Verified(
            employee = matchedEmployee,
            eventType = AttendanceEventType.CHECK_OUT,
            verificationMethod = VerificationMethod.fromWire(lock.verificationMethod),
            confidenceScore = lock.confidenceScore,
            timestampLabel = lock.loggedTimeLabel,
            syncedToCloud = lock.logId !in offlineStore.pendingSyncLogIds.value,
            logId = lock.logId,
            liveSelfiePath = lock.liveSelfiePath.ifBlank { null },
            gpsCoordinates = lock.gpsCoordinates,
            locationAddress = lock.locationAddress,
            timeSourceLabel = lock.timeSource,
            pdfReportPath = lock.pdfReportPath,
            isPersistentLock = true,
            deviceSenderAccount = emailReporter?.resolveSenderAccountEmail(),
            emailReportStatus = currentStatus,
            emailEnforcementWarningActive = true,
            emailEnforcementMessage = VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT,
            checkOutEmailTriggeredOrSent = false,
            matchedCnicName = restoredCnicName,
            matchedCnicNumber = matchedEmployee.cnicNumber,
            cnicPhotoPath = restoredCnicPhotoPath,
            isBatchMode = lock.isConsolidatedBatch,
            batchCompletedCount = batchCount,
            batchTotalCount = batchCount,
            batchAllCompleted = true,
            batchPendingEmployeeNames = emptyList(),
            batchDateKey = batchDate
        )

        if (triggerVoice) {
            if (throttleVoice) {
                speakScanGuidance(
                    key = "pending_checkout_lock",
                    message = VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT,
                    minIntervalMs = 3_500L,
                    urgent = true
                )
            } else {
                voiceSpeaker.speak(VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT, flush = true)
            }
        }
        if (emailReminderJob?.isActive != true) {
            startCheckOutEmailEnforcementLoop(
                logId = lock.logId,
                promptMessage = VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
            )
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
                explicitLogId = log.id,
                gpsCoordinates = log.gpsCoordinates,
                locationAddress = log.locationAddress,
                timeSource = log.timeSource
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
            val activeLock = offlineStore.pendingCheckOutLock.value
            val isBatchRetry = activeLock?.logId == logId && activeLock.isConsolidatedBatch
            val targetDateKey = activeLock?.batchDateKey?.ifBlank { log.loggedDateKey } ?: log.loggedDateKey
            val outcome = if (isBatchRetry) {
                val batchEmployees = offlineStore.getRegisteredBiometricEmployees()
                    .ifEmpty { offlineStore.employees.value }
                val dateLogs = recentLogs.filter { it.loggedDateKey == targetDateKey }
                reporter.generateAndSendBatchReport(
                    employees = batchEmployees,
                    logs = dateLogs,
                    dateKey = targetDateKey,
                    selfiePathsByLogId = selfieMap,
                    syncedToCloud = log.id !in pendingCloudIds
                )
            } else {
                val cnicPath = activeLock?.takeIf { it.logId == logId }?.cnicPhotoPath
                    ?.ifBlank { employee.cnicPhotoPath }
                    ?: employee.cnicPhotoPath.ifBlank { null }
                reporter.generateAndSendReport(
                    employee = employee,
                    log = log,
                    eventType = AttendanceEventType.fromWire(log.eventType),
                    verificationMethod = VerificationMethod.fromWire(log.verificationMethod),
                    confidenceScore = log.confidenceScore.toInt(),
                    syncedToCloud = log.id !in pendingCloudIds,
                    recentDailyLogs = recentLogs,
                    liveSelfiePath = selfieMap[log.id],
                    cnicPhotoPath = cnicPath?.ifBlank { null }
                )
            }
            if (outcome == EmailReportStatus.SENT) {
                offlineStore.clearPendingCheckOutLock(logId)
                if (isBatchRetry) {
                    recentLogs.filter { it.loggedDateKey == targetDateKey }.forEach { offlineStore.markEmailSent(it.id) }
                }
                recordServerEmailEvent(
                    log = log,
                    employee = employee,
                    dispatchStatus = EmailReportStatus.SENT.name,
                    pdfFileName = if (isBatchRetry) {
                        "NICH_TEAM_BATCH_${targetDateKey}.pdf"
                    } else {
                        "NICH_${employee.employeeCode}_${log.loggedDateKey}.pdf"
                    }
                )
                val activeVerified = _terminalPhase.value as? TerminalUiPhase.Verified
                if (activeVerified != null && activeVerified.logId == logId && activeVerified.isPersistentLock) {
                    _terminalPhase.update { phase ->
                        if (phase is TerminalUiPhase.Verified && phase.logId == logId) {
                            phase.copy(
                                emailReportStatus = EmailReportStatus.SENT,
                                checkOutEmailTriggeredOrSent = true,
                                isPersistentLock = false,
                                emailEnforcementWarningActive = false
                            )
                        } else {
                            phase
                        }
                    }
                    emailReminderJob?.cancel()
                    voiceSpeaker.speak(VoicePromptScript.emailDispatchedPrompt(openedDeviceApp = false), flush = true)
                    scheduleAutoResetToReady()
                }
            }
        }
        triggerServerSyncAsync()
    }

    fun openRegistrationScreen() {
        val activeLock = offlineStore.pendingCheckOutLock.value
        if (activeLock != null) {
            restorePhaseFromPendingLock(activeLock, triggerVoice = true)
            return
        }
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        emailReminderJob?.cancel()
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
        speakWelcomeIfNeeded(force = true)
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
        triggerServerSyncAsync()
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
     * Launches continuous hands-free biometric face scanning with 50%+ fast verification.
     */
    fun startVipBiometricScan() {
        startVipBiometricScan(listOf(LivenessDirection.CENTER_VERIFY))
    }

    fun startVipBiometricScan(
        livenessSequence: List<LivenessDirection>,
        preserveCameraReady: Boolean = false
    ) {
        val activeLock = offlineStore.pendingCheckOutLock.value
        if (activeLock != null) {
            restorePhaseFromPendingLock(activeLock, triggerVoice = true)
            return
        }
        val normalizedSequence = if (livenessSequence.isEmpty()) {
            listOf(LivenessDirection.CENTER_VERIFY)
        } else {
            livenessSequence
        }
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        emailCountdownJob?.cancel()
        scanMatchInFlight = false
        cnicCommitInFlight = false
        consecutiveMatchCount = 0
        livenessHoldCount = 0
        lastMatchedEmployeeId = null
        latestCapturedSelfiePath = null
        analyzedFaceFrames = 0
        rejectedFaceFrames = 0
        highestRejectedConfidence = 0
        antiSpoofingTracker.reset()

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
        val currentCnic = _terminalPhase.value as? TerminalUiPhase.CnicScanning
        if (currentCnic != null) {
            if (!currentCnic.cameraReady) {
                _terminalPhase.update { phase ->
                    if (phase is TerminalUiPhase.CnicScanning) phase.copy(cameraReady = true) else phase
                }
            }
            return
        }

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

        if (!_showRegistrationScreen.value) {
            speakWelcomeIfNeeded(force = false)
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
        when (val current = _terminalPhase.value) {
            is TerminalUiPhase.Scanning -> {
                if (current.cameraReady || scanMatchInFlight) return
            }
            is TerminalUiPhase.CnicScanning -> {
                if (current.cameraReady || cnicCommitInFlight) return
            }
            else -> return
        }
        scanTimerJob?.cancel()
        _terminalPhase.value = TerminalUiPhase.Failure(
            title = "Camera Hardware Unavailable",
            message = "Could not initialize camera sensor ($reason). Check permissions and retry."
        )
    }

    fun onLiveFaceFrameAnalyzed(frame: FaceAnalysisFrame) {
        val activeLock = offlineStore.pendingCheckOutLock.value
        if (activeLock != null) {
            restorePhaseFromPendingLock(
                lock = activeLock,
                triggerVoice = frame.faceDetected,
                throttleVoice = true
            )
            return
        }
        val current = _terminalPhase.value as? TerminalUiPhase.Scanning ?: return

        val livenessResult = antiSpoofingTracker.evaluate(frame)

        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Scanning) {
                phase.copy(
                    faceDetected = frame.faceDetected,
                    eyesLocked = frame.eyesLocked,
                    noseMustacheLocked = frame.noseMustacheLocked,
                    jawBeardLocked = frame.jawBeardLocked,
                    livenessVerified = livenessResult.livenessVerified,
                    spoofSuspected = livenessResult.spoofSuspected,
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
            speakScanGuidance("liveness_${activeDirection.name}", VoicePromptScript.directionalLivenessPrompt(activeDirection))
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
                    speakScanGuidance(
                        key = "liveness_${nextDirection.name}",
                        message = VoicePromptScript.directionalLivenessPrompt(nextDirection),
                        minIntervalMs = 1_500L
                    )
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
            speakScanGuidance("alignment_guide", VoicePromptScript.alignmentGuidePrompt())
            return
        }

        // Strict Anti-Spoofing Gate: reject static printed photos & mobile phone screenshots
        if (!livenessResult.livenessVerified) {
            consecutiveMatchCount = 0
            _terminalPhase.update { phase ->
                if (phase is TerminalUiPhase.Scanning) {
                    phase.copy(
                        livenessVerified = false,
                        spoofSuspected = livenessResult.spoofSuspected,
                        qualityHint = livenessResult.statusPrompt
                    )
                } else {
                    phase
                }
            }
            if (livenessResult.spoofSuspected) {
                speakScanGuidance(
                    key = "spoof_alert",
                    message = VoicePromptScript.spoofDetectedPrompt(),
                    urgent = true
                )
            } else {
                speakScanGuidance("liveness_challenge", VoicePromptScript.livenessChallengePrompt())
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
                        "No registered faces in local storage"
                    } else {
                        "Unrecognized face (${evaluation.bestConfidencePercent}% • need ${FaceBiometricMatcher.MATCH_THRESHOLD_PERCENT}%+)"
                    }
                    phase.copy(qualityHint = hint)
                } else {
                    phase
                }
            }
            if (rejectedFaceFrames >= 3) {
                speakScanGuidance("unrecognized_face", VoicePromptScript.unrecognizedFacePrompt())
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

        // Step A complete: transition seamlessly to rear-camera Step B CNIC Front-Side verification
        if (consecutiveMatchCount >= REQUIRED_CONFIRMING_FRAMES &&
            match.confidencePercent >= FaceBiometricMatcher.MATCH_THRESHOLD_PERCENT
        ) {
            scanMatchInFlight = true
            cnicCommitInFlight = false
            scanTimerJob?.cancel()
            val method = if (current.attempt == 1) {
                VerificationMethod.FACE_SCAN_1
            } else {
                VerificationMethod.FACE_SCAN_2
            }
            val capturedSelfie = frame.selfieImagePath ?: latestCapturedSelfiePath

            _terminalPhase.value = TerminalUiPhase.CnicScanning(
                employee = match.employee,
                faceConfidenceScore = match.confidencePercent,
                verificationMethod = method,
                liveSelfiePath = capturedSelfie,
                cameraReady = false,
                statusHeadline = "STEP B • HOLD ${match.employee.name.uppercase()} CNIC INSIDE GREEN FRAME"
            )
            voiceSpeaker.speak(
                VoicePromptScript.switchToCnicStepPrompt(match.employee.name),
                flush = true
            )
        }
    }

    /**
     * Step B: Evaluates live rear-camera OCR frames inside the Green Guide Frame against the
     * face-verified employee's identity/CNIC, completing attendance upon exact match.
     */
    fun onCnicFrameAnalyzed(frame: CnicScanFrame) {
        val activeLock = offlineStore.pendingCheckOutLock.value
        if (activeLock != null) {
            restorePhaseFromPendingLock(
                lock = activeLock,
                triggerVoice = frame.cardDetected,
                throttleVoice = true
            )
            return
        }
        val current = _terminalPhase.value as? TerminalUiPhase.CnicScanning ?: return
        if (cnicCommitInFlight) return

        val outcome = CnicOcrParser.verifyAgainstEmployee(frame, current.employee)
        val displayCnicNum = outcome.matchedCnicNumber.ifBlank { frame.cnicNumber }
        val displayCnicName = outcome.matchedName
            .takeUnless { CnicOcrParser.isGarbledName(it) }
            ?: frame.extractedName.takeUnless { CnicOcrParser.isGarbledName(it) }.orEmpty()

        if (frame.cardDetected && frame.insideGreenFrame && outcome.matched) {
            cnicCommitInFlight = true
            val resolvedName = CnicOcrParser.resolveVerifiedDisplayName(
                observedName = displayCnicName,
                employee = current.employee
            )
            val resolvedCnicNumber = displayCnicNum.ifBlank { current.employee.cnicNumber }
            _terminalPhase.update { phase ->
                if (phase is TerminalUiPhase.CnicScanning) {
                    phase.copy(
                        cardDetected = true,
                        insideGreenFrame = true,
                        detectedCnicName = resolvedName,
                        detectedCnicNumber = resolvedCnicNumber,
                        qualityHint = null
                    )
                } else {
                    phase
                }
            }
            viewModelScope.launch {
                commitVerifiedAttendance(
                    employee = current.employee,
                    method = current.verificationMethod,
                    confidence = current.faceConfidenceScore,
                    explicitEventType = null,
                    liveSelfiePath = current.liveSelfiePath,
                    matchedCnicName = resolvedName,
                    matchedCnicNumber = resolvedCnicNumber,
                    cnicPhotoPath = frame.cnicImagePath
                )
            }
            return
        }

        val hintText = if (!frame.cardDetected || !frame.insideGreenFrame) {
            frame.qualityIssue ?: "Hold CNIC Front Side inside the Green Guide Frame"
        } else {
            outcome.reason
        }
        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.CnicScanning) {
                phase.copy(
                    cardDetected = frame.cardDetected,
                    insideGreenFrame = frame.insideGreenFrame,
                    detectedCnicName = displayCnicName,
                    detectedCnicNumber = displayCnicNum,
                    qualityHint = hintText
                )
            } else {
                phase
            }
        }
        if (frame.cardDetected && frame.insideGreenFrame && !outcome.matched &&
            (displayCnicName.isNotBlank() || displayCnicNum.isNotBlank() || frame.rawOcrText.isNotBlank())
        ) {
            speakScanGuidance(
                key = "cnic_mismatch",
                message = VoicePromptScript.cnicMismatchPrompt(current.employee.name),
                minIntervalMs = 4_500L,
                urgent = true
            )
        }
    }

    fun cancelCnicStepToFaceScan() {
        startVipBiometricScan(
            livenessSequence = listOf(LivenessDirection.CENTER_VERIFY),
            preserveCameraReady = false
        )
    }

    private suspend fun commitVerifiedAttendance(
        employee: Employee,
        method: VerificationMethod,
        confidence: Int,
        explicitEventType: AttendanceEventType?,
        liveSelfiePath: String? = null,
        matchedCnicName: String = "",
        matchedCnicNumber: String = "",
        cnicPhotoPath: String? = null
    ) {
        val networkSnapshot = runCatching { activeTimeProvider.syncNow() }
            .getOrElse { activeTimeProvider.currentSnapshot() }
        _networkTimeStatus.value = networkSnapshot

        val now = activeTimeProvider.currentCalendar()
        val eventType = explicitEventType ?: AttendanceTimeRules.currentEventType(now)
        val timeLabel = AttendanceTimeRules.formatTimeLabel(now)
        val dateKey = AttendanceTimeRules.formatDateKey(now)
        val locationSnapshot = runCatching { locationProvider.captureCurrentLocation() }
            .getOrNull()

        val gpsCoords = locationSnapshot?.coordinatesLabel.orEmpty()
        val readableAddr = locationSnapshot?.readableAddress.orEmpty()
        val timeSourceLabel = networkSnapshot.sourceLabel

        val resolvedCnicDisplayName = CnicOcrParser.resolveVerifiedDisplayName(
            observedName = matchedCnicName,
            employee = employee
        )
        val resolvedCnicNumber = matchedCnicNumber.ifBlank { employee.cnicNumber }

        lastVerifiedEmployeeId = employee.id
        lastVerifiedTimestampMs = System.currentTimeMillis()

        // Persist immediately to local storage with NTP-locked time, verified GPS location, & CNIC match
        val localEntry = offlineStore.recordLocalAttendance(
            employee = employee,
            eventType = eventType,
            verificationMethod = method,
            confidenceScore = confidence,
            loggedTimeLabel = timeLabel,
            loggedDateKey = dateKey,
            synced = false,
            gpsCoordinates = gpsCoords,
            locationAddress = readableAddr,
            timeSource = timeSourceLabel,
            matchedCnicName = resolvedCnicDisplayName,
            matchedCnicNumber = resolvedCnicNumber
        )
        val durableSelfiePath = offlineStore.persistSelfieForLog(localEntry.id, liveSelfiePath)
            ?: liveSelfiePath
        val durableCnicPath = offlineStore.persistCnicPhotoForLog(localEntry.id, cnicPhotoPath)
            ?: cnicPhotoPath

        // Evaluate Single- vs Multi-Employee daily completion status AFTER recording this event
        val batchStatus = offlineStore.getBatchCycleStatus(dateKey)
        val isBatchMode = batchStatus.isMultiEmployeeMode
        val isBatchAllDone = if (isBatchMode) batchStatus.allCompleted else true
        val isReadyToDispatchEmail = (eventType == AttendanceEventType.CHECK_OUT) &&
            (!isBatchMode || batchStatus.allCompleted)
        val isWaitingOthersInBatch = (eventType == AttendanceEventType.CHECK_OUT) &&
            isBatchMode &&
            !batchStatus.allCompleted

        val initialEmailStatus = if (isReadyToDispatchEmail) {
            "PENDING"
        } else {
            "NOT_APPLICABLE"
        }
        offlineStore.markServerEmployeePending(employee.id)
        offlineStore.markServerLogPending(
            logId = localEntry.id,
            ntpEpochMillis = networkSnapshot.epochMillis,
            emailDispatchStatus = initialEmailStatus
        )
        triggerServerSyncAsync()

        val senderAccount = emailReporter?.resolveSenderAccountEmail()
        emailReminderJob?.cancel()

        _terminalPhase.value = TerminalUiPhase.Verified(
            employee = employee,
            eventType = eventType,
            verificationMethod = method,
            confidenceScore = confidence,
            timestampLabel = timeLabel,
            syncedToCloud = false,
            logId = localEntry.id,
            liveSelfiePath = durableSelfiePath,
            gpsCoordinates = gpsCoords,
            locationAddress = readableAddr,
            timeSourceLabel = timeSourceLabel,
            deviceSenderAccount = senderAccount,
            emailReportStatus = EmailReportStatus.NEEDS_CONFIG,
            emailEnforcementWarningActive = false,
            emailEnforcementMessage = null,
            checkOutEmailTriggeredOrSent = !isReadyToDispatchEmail,
            matchedCnicName = resolvedCnicDisplayName,
            matchedCnicNumber = resolvedCnicNumber,
            cnicPhotoPath = durableCnicPath,
            isBatchMode = isBatchMode,
            batchCompletedCount = if (isBatchMode) batchStatus.completedCount else 1,
            batchTotalCount = batchStatus.totalEnrolledCount.coerceAtLeast(1),
            batchAllCompleted = isBatchAllDone,
            batchPendingEmployeeNames = batchStatus.incompleteEmployeeNames,
            batchDateKey = dateKey
        )

        voiceSpeaker.speak(
            VoicePromptScript.verificationPrompt(
                employeeName = resolvedCnicDisplayName,
                eventType = eventType,
                isBatchWaitingOthers = isWaitingOthersInBatch
            ),
            flush = true
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
                        explicitLogId = localEntry.id,
                        gpsCoordinates = gpsCoords,
                        locationAddress = readableAddr,
                        timeSource = timeSourceLabel
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

        // Conditional Email Notification & Strict Smart Batch / Check-Out Enforcement:
        // - CHECK-IN or Multi-Employee awaiting remaining staff: Save locally & return to scanning.
        // - CHECK-OUT ready (Single Employee OR all Multi-Employee staff done): Persist durable lock & enforce email.
        if (!isReadyToDispatchEmail) {
            scheduleAutoResetToReady()
        } else {
            val totalEnrolled = batchStatus.totalEnrolledCount.coerceAtLeast(1)
            offlineStore.savePendingCheckOutLock(
                PendingCheckOutLock(
                    logId = localEntry.id,
                    employeeId = employee.id,
                    employeeCode = employee.employeeCode,
                    employeeName = resolvedCnicDisplayName,
                    department = employee.department,
                    roleTitle = employee.roleTitle,
                    avatarKey = employee.avatarKey,
                    localEmployeePhotoPath = employee.localPhotoPath,
                    verificationMethod = method.wireValue,
                    confidenceScore = confidence,
                    loggedTimeLabel = timeLabel,
                    loggedDateKey = dateKey,
                    gpsCoordinates = gpsCoords,
                    locationAddress = readableAddr,
                    timeSource = timeSourceLabel,
                    liveSelfiePath = durableSelfiePath.orEmpty(),
                    isConsolidatedBatch = isBatchMode,
                    batchDateKey = if (isBatchMode) dateKey else "",
                    batchEmployeeCount = totalEnrolled,
                    matchedCnicName = resolvedCnicDisplayName,
                    cnicPhotoPath = durableCnicPath.orEmpty()
                )
            )
            val reporter = emailReporter
            if (reporter != null && reporter.hasConfiguredSmtpCredentials()) {
                _terminalPhase.update { phase ->
                    if (phase is TerminalUiPhase.Verified && phase.logId == localEntry.id) {
                        phase.copy(emailReportStatus = EmailReportStatus.DISPATCHING)
                    } else {
                        phase
                    }
                }
                if (isBatchMode) {
                    launchBatchEmailReportDispatch(
                        logId = localEntry.id,
                        employee = employee,
                        log = localEntry,
                        dateKey = dateKey,
                        syncedToCloud = false
                    )
                } else {
                    launchEmailReportDispatch(
                        employee = employee,
                        log = localEntry,
                        eventType = eventType,
                        verificationMethod = method,
                        confidence = confidence,
                        syncedToCloud = false,
                        liveSelfiePath = durableSelfiePath,
                        cnicPhotoPath = durableCnicPath,
                        rescheduleResetAfter = false
                    )
                }
            } else {
                // Pre-generate & persist the structured PDF report immediately for offline lock / Gmail dispatch
                if (reporter != null) {
                    val recentLogsSnapshot = offlineStore.logs.value
                    val batchEmployees = offlineStore.getRegisteredBiometricEmployees()
                        .ifEmpty { offlineStore.employees.value }
                    val dateLogsSnapshot = recentLogsSnapshot.filter { it.loggedDateKey == dateKey }
                    val selfiesSnapshot = offlineStore.getSelfiePathsByLogId()
                    pdfPreGenJob?.cancel()
                    pdfPreGenJob = viewModelScope.launch {
                        runCatching {
                            if (isBatchMode) {
                                reporter.generateBatchReportPdfFile(
                                    employees = batchEmployees,
                                    logs = dateLogsSnapshot,
                                    dateKey = dateKey,
                                    selfiePathsByLogId = selfiesSnapshot
                                )
                            } else {
                                reporter.generateReportPdfFile(
                                    employee = employee,
                                    log = localEntry,
                                    eventType = eventType,
                                    verificationMethod = method,
                                    confidenceScore = confidence,
                                    syncedToCloud = false,
                                    recentDailyLogs = recentLogsSnapshot,
                                    liveSelfiePath = durableSelfiePath,
                                    cnicPhotoPath = durableCnicPath
                                )
                            }
                        }.onSuccess { pdfFile ->
                            offlineStore.updatePendingCheckOutPdfPath(localEntry.id, pdfFile.absolutePath)
                            _terminalPhase.update { phase ->
                                if (phase is TerminalUiPhase.Verified && phase.logId == localEntry.id) {
                                    phase.copy(pdfReportPath = pdfFile.absolutePath)
                                } else {
                                    phase
                                }
                            }
                        }
                    }
                }
                startCheckOutEmailEnforcementLoop(
                    logId = localEntry.id,
                    promptMessage = VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT
                )
            }
        }
    }

    /**
     * Blocks any attempt to skip, close, or dismiss Check-Out before the mandatory email action
     * has been triggered/sent, activating both the persistent warning banner and AI voice prompt.
     */
    fun attemptSkipOrDismissCheckOut() {
        val current = _terminalPhase.value as? TerminalUiPhase.Verified ?: return
        if (current.isCheckOutEmailCompleted && offlineStore.pendingCheckOutLock.value == null) {
            resetToReady()
            return
        }
        val activePrompt = if (current.isPersistentLock) {
            VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
        } else {
            VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT
        }
        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                phase.copy(
                    emailEnforcementWarningActive = true,
                    emailEnforcementMessage = activePrompt
                )
            } else {
                phase
            }
        }
        voiceSpeaker.speak(activePrompt, flush = true)
    }

    private fun startCheckOutEmailEnforcementLoop(
        logId: String,
        promptMessage: String = VoicePromptScript.MANDATORY_EMAIL_ENFORCEMENT_PROMPT
    ) {
        emailReminderJob?.cancel()
        emailReminderJob = viewModelScope.launch {
            while (isActive) {
                delay(EMAIL_ENFORCEMENT_INTERVAL_MS)
                val current = _terminalPhase.value as? TerminalUiPhase.Verified ?: break
                if (current.logId != logId ||
                    (current.isCheckOutEmailCompleted && offlineStore.pendingCheckOutLock.value == null)
                ) {
                    break
                }
                if (current.emailReportStatus != EmailReportStatus.DISPATCHING &&
                    current.emailAutoUnlockSecondsRemaining == null
                ) {
                    val activePrompt = if (current.isPersistentLock) {
                        VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
                    } else {
                        promptMessage
                    }
                    _terminalPhase.update { phase ->
                        if (phase is TerminalUiPhase.Verified && phase.logId == logId) {
                            phase.copy(
                                emailEnforcementWarningActive = true,
                                emailEnforcementMessage = activePrompt
                            )
                        } else {
                            phase
                        }
                    }
                    voiceSpeaker.speak(activePrompt, flush = true)
                }
            }
        }
    }

    /**
     * Dispatches either the single-employee PDF report or the consolidated multi-employee
     * batch PDF report to Nichemployeeattendance@gmail.com when enabled and tapped by the user,
     * then initiates a 5-second countdown timer that automatically resolves & clears the pending
     * email lock and returns the terminal to active unlocked scanning.
     */
    fun sendEmailAndFinish(onLaunchEmailIntent: (Intent) -> Unit) {
        val current = _terminalPhase.value as? TerminalUiPhase.Verified ?: run {
            resetToReady()
            return
        }
        if (current.isBatchMode && !current.batchAllCompleted) return
        if (current.emailAutoUnlockSecondsRemaining != null ||
            current.emailReportStatus == EmailReportStatus.DISPATCHING
        ) {
            return
        }

        resetTimerJob?.cancel()
        emailReminderJob?.cancel()
        emailCountdownJob?.cancel()

        val matchingLog = buildLogForVerifiedPhase(current)
        val snapshotLogs = offlineStore.logs.value
        val targetDateKey = current.batchDateKey.ifBlank {
            matchingLog.loggedDateKey.ifBlank {
                AttendanceTimeRules.formatDateKey(activeTimeProvider.currentCalendar())
            }
        }
        val reporter = emailReporter
        val smtpConfigured = reporter?.hasConfiguredSmtpCredentials() == true

        _terminalPhase.update { phase ->
            if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                phase.copy(
                    emailReportStatus = EmailReportStatus.DISPATCHING,
                    emailEnforcementWarningActive = false,
                    emailAutoUnlockSecondsRemaining = EMAIL_AUTO_UNLOCK_SECONDS
                )
            } else {
                phase
            }
        }

        if (reporter != null) {
            viewModelScope.launch {
                var openedDeviceShare = false
                if (smtpConfigured) {
                    val outcome = if (current.isBatchMode) {
                        val batchEmployees = offlineStore.getRegisteredBiometricEmployees()
                            .ifEmpty { offlineStore.employees.value }
                        val dateLogs = snapshotLogs.filter { it.loggedDateKey == targetDateKey }
                        val selfiesMap = offlineStore.getSelfiePathsByLogId()
                        reporter.generateAndSendBatchReport(
                            employees = batchEmployees,
                            logs = dateLogs,
                            dateKey = targetDateKey,
                            selfiePathsByLogId = selfiesMap,
                            syncedToCloud = current.syncedToCloud
                        )
                    } else {
                        reporter.generateAndSendReport(
                            employee = current.employee,
                            log = matchingLog,
                            eventType = current.eventType,
                            verificationMethod = current.verificationMethod,
                            confidenceScore = current.confidenceScore,
                            syncedToCloud = current.syncedToCloud,
                            recentDailyLogs = snapshotLogs,
                            liveSelfiePath = current.liveSelfiePath,
                            cnicPhotoPath = current.cnicPhotoPath
                        )
                    }
                    recordServerEmailEvent(
                        log = matchingLog,
                        employee = current.employee,
                        dispatchStatus = outcome.name,
                        pdfFileName = current.pdfReportPath.substringAfterLast('/')
                    )
                    _terminalPhase.update { phase ->
                        if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                            phase.copy(emailReportStatus = outcome)
                        } else {
                            phase
                        }
                    }
                } else {
                    runCatching {
                        pdfPreGenJob?.join()
                        val latestPdfPath = (_terminalPhase.value as? TerminalUiPhase.Verified)
                            ?.takeIf { it.logId == current.logId }
                            ?.pdfReportPath
                            ?.ifBlank { offlineStore.pendingCheckOutLock.value?.pdfReportPath.orEmpty() }
                            ?: current.pdfReportPath
                        val existingPdf = latestPdfPath
                            .takeIf { it.isNotBlank() }
                            ?.let { java.io.File(it) }
                            ?.takeIf { it.exists() && it.length() > 0L }
                        val batchEmployees = offlineStore.getRegisteredBiometricEmployees()
                            .ifEmpty { offlineStore.employees.value }
                        val dateLogs = snapshotLogs.filter { it.loggedDateKey == targetDateKey }
                        val selfiesMap = offlineStore.getSelfiePathsByLogId()

                        val pdfFile = existingPdf ?: if (current.isBatchMode) {
                            reporter.generateBatchReportPdfFile(
                                employees = batchEmployees,
                                logs = dateLogs,
                                dateKey = targetDateKey,
                                selfiePathsByLogId = selfiesMap
                            )
                        } else {
                            reporter.generateReportPdfFile(
                                employee = current.employee,
                                log = matchingLog,
                                eventType = current.eventType,
                                verificationMethod = current.verificationMethod,
                                confidenceScore = current.confidenceScore,
                                syncedToCloud = current.syncedToCloud,
                                recentDailyLogs = snapshotLogs,
                                liveSelfiePath = current.liveSelfiePath,
                                cnicPhotoPath = current.cnicPhotoPath
                            )
                        }
                        offlineStore.updatePendingCheckOutPdfPath(matchingLog.id, pdfFile.absolutePath)
                        val gmailIntent = if (current.isBatchMode) {
                            reporter.buildDeviceGmailBatchShareIntent(
                                pdfFile = pdfFile,
                                employees = batchEmployees,
                                logs = dateLogs,
                                dateKey = targetDateKey,
                                syncedToCloud = current.syncedToCloud
                            )
                        } else {
                            reporter.buildDeviceGmailShareIntent(
                                pdfFile = pdfFile,
                                employee = current.employee,
                                log = matchingLog,
                                eventType = current.eventType,
                                verificationMethod = current.verificationMethod,
                                confidenceScore = current.confidenceScore,
                                syncedToCloud = current.syncedToCloud
                            )
                        }
                        recordServerEmailEvent(
                            log = matchingLog,
                            employee = current.employee,
                            dispatchStatus = "DEVICE_GMAIL_INTENT",
                            pdfFileName = pdfFile.name
                        )
                        onLaunchEmailIntent(gmailIntent)
                        openedDeviceShare = true
                    }.onFailure { err ->
                        Log.w(TAG, "Email intent preparation deferred: ${err.message}", err)
                    }
                    _terminalPhase.update { phase ->
                        if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                            phase.copy(emailReportStatus = EmailReportStatus.QUEUED_OFFLINE)
                        } else {
                            phase
                        }
                    }
                }
                triggerServerSyncAsync()
                voiceSpeaker.speak(
                    VoicePromptScript.emailDispatchedPrompt(openedDeviceApp = openedDeviceShare),
                    flush = true
                )
            }
        }

        // 5-second countdown timer: resolves & clears pending email status and unlocks terminal automatically
        emailCountdownJob = viewModelScope.launch {
            for (remaining in EMAIL_AUTO_UNLOCK_SECONDS downTo 1) {
                _terminalPhase.update { phase ->
                    if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                        phase.copy(emailAutoUnlockSecondsRemaining = remaining)
                    } else {
                        phase
                    }
                }
                delay(1_000L)
            }

            offlineStore.clearPendingCheckOutLock(matchingLog.id)
            if (current.isBatchMode) {
                offlineStore.logs.value
                    .filter { it.loggedDateKey == targetDateKey }
                    .forEach { offlineStore.markEmailSent(it.id) }
            }
            if (smtpConfigured) {
                retryPendingEmailReports()
            }
            _terminalPhase.update { phase ->
                if (phase is TerminalUiPhase.Verified && phase.logId == current.logId) {
                    phase.copy(
                        checkOutEmailTriggeredOrSent = true,
                        isPersistentLock = false,
                        emailEnforcementWarningActive = false,
                        emailAutoUnlockSecondsRemaining = null
                    )
                } else {
                    phase
                }
            }
            if (reporter == null || !smtpConfigured) {
                voiceSpeaker.speak(
                    VoicePromptScript.emailDispatchedPrompt(openedDeviceApp = false),
                    flush = true
                )
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
            cnicPhotoPath = current.cnicPhotoPath,
            rescheduleResetAfter = true
        )
    }

    private fun buildLogForVerifiedPhase(current: TerminalUiPhase.Verified): AttendanceLog {
        return offlineStore.logs.value.firstOrNull { it.id == current.logId }
            ?: AttendanceLog(
                id = current.logId.ifBlank { "manual_${System.currentTimeMillis()}" },
                employeeId = current.employee.id,
                employeeCode = current.employee.employeeCode,
                employeeName = current.matchedCnicName.ifBlank { current.employee.name },
                department = current.employee.department,
                avatarKey = current.employee.avatarKey,
                eventType = current.eventType.wireValue,
                verificationMethod = current.verificationMethod.wireValue,
                confidenceScore = current.confidenceScore.toLong().coerceIn(0L, 100L),
                loggedTimeLabel = current.timestampLabel,
                loggedDateKey = AttendanceTimeRules.formatDateKey(activeTimeProvider.currentCalendar()),
                gpsCoordinates = current.gpsCoordinates,
                locationAddress = current.locationAddress,
                timeSource = current.timeSourceLabel,
                matchedCnicName = current.matchedCnicName,
                matchedCnicNumber = current.matchedCnicNumber
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
        cnicPhotoPath: String? = null,
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
                liveSelfiePath = liveSelfiePath,
                cnicPhotoPath = cnicPhotoPath
            )
            if (outcome == EmailReportStatus.QUEUED_OFFLINE) {
                offlineStore.markEmailPending(log.id)
            } else if (outcome == EmailReportStatus.SENT) {
                offlineStore.clearPendingCheckOutLock(log.id)
                emailReminderJob?.cancel()
                retryPendingEmailReports()
                voiceSpeaker.speak(
                    VoicePromptScript.emailDispatchedPrompt(openedDeviceApp = false),
                    flush = false
                )
            }
            recordServerEmailEvent(
                log = log,
                employee = employee,
                dispatchStatus = outcome.name,
                pdfFileName = "NICH_${employee.employeeCode}_${log.loggedDateKey}.pdf"
            )
            triggerServerSyncAsync()
            val lockFailedCheckOut = outcome != EmailReportStatus.SENT && eventType == AttendanceEventType.CHECK_OUT
            _terminalPhase.update { current ->
                if (current is TerminalUiPhase.Verified && current.logId == log.id) {
                    current.copy(
                        emailReportStatus = outcome,
                        checkOutEmailTriggeredOrSent = outcome == EmailReportStatus.SENT || current.checkOutEmailTriggeredOrSent,
                        isPersistentLock = if (outcome == EmailReportStatus.SENT) false else (current.isPersistentLock || lockFailedCheckOut),
                        emailEnforcementWarningActive = if (outcome == EmailReportStatus.SENT) false else lockFailedCheckOut,
                        emailEnforcementMessage = if (lockFailedCheckOut) {
                            VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
                        } else {
                            current.emailEnforcementMessage
                        }
                    )
                } else {
                    current
                }
            }
            if (lockFailedCheckOut) {
                voiceSpeaker.speak(VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT, flush = true)
                startCheckOutEmailEnforcementLoop(
                    logId = log.id,
                    promptMessage = VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
                )
            }
            if (rescheduleResetAfter && outcome == EmailReportStatus.SENT) {
                scheduleAutoResetToReady()
            }
        }
    }

    private fun launchBatchEmailReportDispatch(
        logId: String,
        employee: Employee,
        log: AttendanceLog,
        dateKey: String,
        syncedToCloud: Boolean
    ) {
        val reporter = emailReporter ?: return
        val batchEmployees = offlineStore.getRegisteredBiometricEmployees()
            .ifEmpty { offlineStore.employees.value }
        val dateLogs = offlineStore.logs.value.filter { it.loggedDateKey == dateKey }
        val selfiesMap = offlineStore.getSelfiePathsByLogId()
        emailDispatchJob?.cancel()
        emailDispatchJob = viewModelScope.launch {
            val outcome = reporter.generateAndSendBatchReport(
                employees = batchEmployees,
                logs = dateLogs,
                dateKey = dateKey,
                selfiePathsByLogId = selfiesMap,
                syncedToCloud = syncedToCloud
            )
            if (outcome == EmailReportStatus.QUEUED_OFFLINE) {
                offlineStore.markEmailPending(logId)
            } else if (outcome == EmailReportStatus.SENT) {
                offlineStore.clearPendingCheckOutLock(logId)
                dateLogs.forEach { offlineStore.markEmailSent(it.id) }
                emailReminderJob?.cancel()
                retryPendingEmailReports()
                voiceSpeaker.speak(
                    VoicePromptScript.emailDispatchedPrompt(openedDeviceApp = false),
                    flush = false
                )
            }
            recordServerEmailEvent(
                log = log,
                employee = employee,
                dispatchStatus = outcome.name,
                pdfFileName = "NICH_TEAM_BATCH_${dateKey}.pdf"
            )
            triggerServerSyncAsync()
            val lockFailedCheckOut = outcome != EmailReportStatus.SENT
            _terminalPhase.update { current ->
                if (current is TerminalUiPhase.Verified && current.logId == logId) {
                    current.copy(
                        emailReportStatus = outcome,
                        checkOutEmailTriggeredOrSent = outcome == EmailReportStatus.SENT || current.checkOutEmailTriggeredOrSent,
                        isPersistentLock = if (outcome == EmailReportStatus.SENT) false else true,
                        emailEnforcementWarningActive = if (outcome == EmailReportStatus.SENT) false else lockFailedCheckOut,
                        emailEnforcementMessage = if (lockFailedCheckOut) {
                            VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
                        } else {
                            current.emailEnforcementMessage
                        }
                    )
                } else {
                    current
                }
            }
            if (lockFailedCheckOut) {
                voiceSpeaker.speak(VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT, flush = true)
                startCheckOutEmailEnforcementLoop(
                    logId = logId,
                    promptMessage = VoicePromptScript.PENDING_CHECKOUT_LOCK_PROMPT
                )
            }
        }
    }

    private fun recordServerEmailEvent(
        log: AttendanceLog,
        employee: Employee,
        dispatchStatus: String,
        pdfFileName: String
    ) {
        offlineStore.updateServerLogEmailStatus(log.id, dispatchStatus)
        if (log.eventType == AttendanceEventType.CHECK_OUT.wireValue) {
            val meta = offlineStore.getServerLogMetadata(log.id)
            offlineStore.enqueueServerEmailReport(
                PendingServerEmailReport(
                    reportId = "email_${log.id}",
                    logId = log.id,
                    employeeId = employee.id,
                    employeeCode = employee.employeeCode,
                    employeeName = employee.name,
                    department = employee.department,
                    eventType = log.eventType,
                    recipientEmail = AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL,
                    dispatchStatus = dispatchStatus,
                    pdfFileName = pdfFileName,
                    gpsCoordinates = log.gpsCoordinates,
                    locationAddress = log.locationAddress,
                    timeSource = log.timeSource,
                    ntpEpochMillis = meta.ntpEpochMillis
                )
            )
        }
    }

    private fun triggerServerSyncAsync() {
        val client = serverSyncClient ?: return
        if (!client.isConfigured()) return
        viewModelScope.launch {
            runCatching {
                client.flushPending(offlineStore, currentUserId)
            }.onFailure { err ->
                Log.w(TAG, "Background server sync deferred: ${err.message}")
            }
        }
    }

    private fun speakWelcomeIfNeeded(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (now - welcomeSpokenAtMs) < WELCOME_THROTTLE_MS) return
        welcomeSpokenAtMs = now
        lastVoicePromptKey = "welcome"
        lastVoicePromptAtMs = now
        voiceSpeaker.speak(VoicePromptScript.welcomeScannerPrompt(), flush = true)
    }

    private fun speakScanGuidance(
        key: String,
        message: String,
        minIntervalMs: Long = SCAN_GUIDANCE_THROTTLE_MS,
        urgent: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (lastVoicePromptKey == key && (now - lastVoicePromptAtMs) < minIntervalMs) return
        if (!urgent &&
            lastVoicePromptKey != null &&
            lastVoicePromptKey != "welcome" &&
            lastVoicePromptKey != key &&
            (now - lastVoicePromptAtMs) < GLOBAL_SCAN_VOICE_GAP_MS
        ) {
            return
        }
        lastVoicePromptKey = key
        lastVoicePromptAtMs = now
        voiceSpeaker.speak(message, flush = urgent)
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
        val activeLock = offlineStore.pendingCheckOutLock.value
        val activeVerified = _terminalPhase.value as? TerminalUiPhase.Verified
        if (activeVerified != null && !activeVerified.isCheckOutEmailCompleted) {
            attemptSkipOrDismissCheckOut()
            return
        }
        if (activeLock != null) {
            restorePhaseFromPendingLock(activeLock, triggerVoice = true)
            return
        }
        scanTimerJob?.cancel()
        resetTimerJob?.cancel()
        emailReminderJob?.cancel()
        emailCountdownJob?.cancel()
        scanMatchInFlight = false
        cnicCommitInFlight = false
        consecutiveMatchCount = 0
        livenessHoldCount = 0
        lastMatchedEmployeeId = null
        latestCapturedSelfiePath = null
        antiSpoofingTracker.reset()
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
        emailReminderJob?.cancel()
        emailCountdownJob?.cancel()
        voiceSpeaker.shutdown()
    }

    private companion object {
        const val TAG = "AttendanceVM"
        const val ATTEMPT_DURATION_MS = 7000L
        const val VERIFIED_AUTO_RESET_MS = 2500L
        const val EMAIL_ENFORCEMENT_INTERVAL_MS = 6000L
        const val EMAIL_AUTO_UNLOCK_SECONDS = 5
        const val WELCOME_THROTTLE_MS = 30_000L
        const val SCAN_GUIDANCE_THROTTLE_MS = 5500L
        const val GLOBAL_SCAN_VOICE_GAP_MS = 3200L
        const val REQUIRED_CONFIRMING_FRAMES = 1
        const val LIVENESS_HOLD_FRAMES = 1
    }
}
