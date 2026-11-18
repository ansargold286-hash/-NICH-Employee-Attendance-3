package com.example.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.AttendanceTimeRules
import com.example.data.model.Employee
import com.example.data.model.FaceBiometricMatcher
import com.example.data.model.PasscodeMatchResult
import com.example.data.model.PasscodeResolver
import com.example.data.model.VerificationMethod
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalRegistrationInput(
    val name: String,
    val department: String = "Authorized Staff",
    val photoPath: String,
    val faceSignature: String
)

data class PendingCheckOutLock(
    val logId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeName: String,
    val department: String,
    val roleTitle: String = "Authorized Staff",
    val avatarKey: String = "avatar_1",
    val localEmployeePhotoPath: String = "",
    val verificationMethod: String,
    val confidenceScore: Int,
    val loggedTimeLabel: String,
    val loggedDateKey: String,
    val gpsCoordinates: String = "",
    val locationAddress: String = "",
    val timeSource: String = "",
    val liveSelfiePath: String = "",
    val pdfReportPath: String = "",
    val lockedAtMillis: Long = System.currentTimeMillis()
)

data class ServerLogMetadata(
    val ntpEpochMillis: Long = 0L,
    val emailDispatchStatus: String = ""
)

data class PendingServerEmailReport(
    val reportId: String,
    val logId: String,
    val employeeId: String,
    val employeeCode: String,
    val employeeName: String,
    val department: String,
    val eventType: String,
    val recipientEmail: String,
    val dispatchStatus: String,
    val pdfFileName: String = "",
    val gpsCoordinates: String = "",
    val locationAddress: String = "",
    val timeSource: String = "",
    val ntpEpochMillis: Long = 0L,
    val dispatchedAtEpochMs: Long = System.currentTimeMillis()
)

class OfflineAttendanceStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _employees = MutableStateFlow(loadOrSeedEmployees())
    val employees: StateFlow<List<Employee>> = _employees.asStateFlow()

    private val _logs = MutableStateFlow(loadLogs())
    val logs: StateFlow<List<AttendanceLog>> = _logs.asStateFlow()

    private val _pendingSyncLogIds = MutableStateFlow(loadStringSet(KEY_PENDING_SYNC_IDS))
    val pendingSyncLogIds: StateFlow<Set<String>> = _pendingSyncLogIds.asStateFlow()

    private val _pendingSyncEmployeeIds = MutableStateFlow(loadStringSet(KEY_PENDING_EMPLOYEE_IDS))
    val pendingSyncEmployeeIds: StateFlow<Set<String>> = _pendingSyncEmployeeIds.asStateFlow()

    private val _pendingDeleteEmployeeIds = MutableStateFlow(loadStringSet(KEY_PENDING_DELETE_EMPLOYEE_IDS))
    val pendingDeleteEmployeeIds: StateFlow<Set<String>> = _pendingDeleteEmployeeIds.asStateFlow()

    private val _pendingEmailLogIds = MutableStateFlow(loadStringSet(KEY_PENDING_EMAIL_LOG_IDS))
    val pendingEmailLogIds: StateFlow<Set<String>> = _pendingEmailLogIds.asStateFlow()

    private val _pendingCheckOutLock = MutableStateFlow(loadPendingCheckOutLock())
    val pendingCheckOutLock: StateFlow<PendingCheckOutLock?> = _pendingCheckOutLock.asStateFlow()

    private val _pendingServerLogIds = MutableStateFlow(loadStringSet(KEY_PENDING_SERVER_LOG_IDS))
    val pendingServerLogIds: StateFlow<Set<String>> = _pendingServerLogIds.asStateFlow()

    private val _pendingServerEmployeeIds = MutableStateFlow(loadStringSet(KEY_PENDING_SERVER_EMPLOYEE_IDS))
    val pendingServerEmployeeIds: StateFlow<Set<String>> = _pendingServerEmployeeIds.asStateFlow()

    val isCheckOutEmailLocked: Boolean
        get() = _pendingCheckOutLock.value != null

    fun hasRegisteredFaceProfiles(): Boolean {
        return _employees.value.any { emp ->
            emp.isActive &&
                emp.faceSignature.isNotBlank() &&
                FaceBiometricMatcher.parseSignature(emp.faceSignature) != null
        }
    }

    /**
     * Previously calibrated a static bundled drawable for Muhammad Ansar, which could
     * cause mismatches against other employees. Clear any legacy unverified synthetic
     * vector unless backed by a live captured local photo.
     */
    suspend fun calibrateRegisteredReferenceFaces() {
        val current = _employees.value
        var changed = false
        val cleaned = current.map { emp ->
            val hasRealPhoto = emp.localPhotoPath.isNotBlank() && File(emp.localPhotoPath).exists()
            val isLegacySynthetic = emp.faceSignature == DEFAULT_ANSAR_FACE_SIGNATURE ||
                emp.faceSignature == LEGACY_ANSAR_FACE_SIGNATURE
            if (!hasRealPhoto && isLegacySynthetic) {
                changed = true
                emp.copy(faceSignature = "")
            } else {
                emp
            }
        }
        if (changed) {
            _employees.value = cleaned
            persistEmployees(cleaned)
        }
    }

    fun verifyCode(rawCode: String, defaultEventType: AttendanceEventType): PasscodeMatchResult? {
        return PasscodeResolver.resolve(rawCode, _employees.value, defaultEventType)
    }

    /**
     * Registers a batch of 1 or more locally captured employee profiles (Name + durable
     * Internal Storage portrait photo + extracted ML Kit facial embedding) at once.
     */
    fun registerEmployeesBatch(inputs: List<LocalRegistrationInput>): List<Employee> {
        if (inputs.isEmpty()) return emptyList()

        val createdList = mutableListOf<Employee>()
        var updatedList = _employees.value.toMutableList()
        var nextNum = computeNextEmployeeNumber(updatedList)

        for (input in inputs) {
            val cleanName = input.name.trim()
            if (cleanName.isBlank()) continue

            val empId = "emp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            val empCode = String.format(java.util.Locale.US, "NICH-%04d", nextNum)
            val passcode = String.format(java.util.Locale.US, "%04d", nextNum)
            nextNum++

            val durablePhotoPath = persistRegisteredEmployeePhoto(empId, input.photoPath)

            val newEmployee = Employee(
                id = empId,
                employeeCode = empCode,
                name = cleanName,
                department = input.department.trim().ifEmpty { "Authorized Staff" },
                roleTitle = "Registered Staff",
                passcode = passcode,
                avatarKey = "avatar_local",
                faceSignature = input.faceSignature.trim(),
                isActive = true,
                createdAt = Timestamp.now(),
                updatedAt = Timestamp.now(),
                localPhotoPath = durablePhotoPath
            )
            // Replace if same name already existed cleanly or append new
            updatedList.add(newEmployee)
            createdList.add(newEmployee)
            markEmployeePendingSync(empId)
            markServerEmployeePending(empId)
        }

        val sorted = updatedList.sortedBy { it.employeeCode }
        _employees.value = sorted
        persistEmployees(sorted)
        prefs.edit { putBoolean(KEY_REGISTRATION_COMPLETED, true) }
        return createdList
    }

    private fun computeNextEmployeeNumber(current: List<Employee>): Int {
        val existingMax = current.mapNotNull { emp ->
            emp.employeeCode.substringAfter("-", "").toIntOrNull()
        }.maxOrNull() ?: 4000
        return maxOf(4001, existingMax + 1)
    }

    private fun persistRegisteredEmployeePhoto(employeeId: String, sourcePath: String): String {
        if (sourcePath.isBlank()) return ""
        val srcFile = File(sourcePath)
        if (!srcFile.exists() || srcFile.length() <= 0L) return ""

        return runCatching {
            val dir = File(appContext.filesDir, "employee_photos").apply {
                if (!exists()) mkdirs()
            }
            val safeId = employeeId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val destFile = File(dir, "$safeId.jpg")
            if (srcFile.absolutePath != destFile.absolutePath) {
                srcFile.copyTo(destFile, overwrite = true)
            }
            destFile.absolutePath
        }.getOrElse {
            Log.w(TAG, "Failed to persist registered photo: ${it.message}")
            sourcePath
        }
    }

    fun addEmployee(
        employeeCode: String,
        name: String,
        department: String,
        roleTitle: String,
        passcode: String,
        checkInCode: String = "",
        checkOutCode: String = "",
        avatarKey: String,
        faceSignature: String = "",
        localPhotoPath: String = ""
    ): Employee {
        val empId = "emp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val durablePhotoPath = persistRegisteredEmployeePhoto(empId, localPhotoPath)
        val newEmployee = Employee(
            id = empId,
            employeeCode = employeeCode.trim(),
            name = name.trim(),
            department = department.trim(),
            roleTitle = roleTitle.trim(),
            passcode = passcode.trim(),
            checkInCode = checkInCode.trim(),
            checkOutCode = checkOutCode.trim(),
            avatarKey = avatarKey,
            faceSignature = faceSignature.trim(),
            isActive = true,
            createdAt = Timestamp.now(),
            updatedAt = Timestamp.now(),
            localPhotoPath = durablePhotoPath
        )
        val updated = (_employees.value + newEmployee).sortedBy { it.employeeCode }
        _employees.value = updated
        persistEmployees(updated)
        markEmployeePendingSync(newEmployee.id)
        markServerEmployeePending(newEmployee.id)
        return newEmployee
    }

    fun updateEmployeeFaceSignature(employee: Employee, signature: String) {
        val updated = _employees.value.map {
            if (it.id == employee.id) it.copy(faceSignature = signature.trim(), updatedAt = Timestamp.now()) else it
        }
        _employees.value = updated
        persistEmployees(updated)
        markEmployeePendingSync(employee.id)
        markServerEmployeePending(employee.id)
    }

    fun deleteEmployee(employeeId: String, queueCloudDelete: Boolean = true) {
        val target = _employees.value.firstOrNull { it.id == employeeId }
        if (target != null && target.localPhotoPath.isNotBlank()) {
            runCatching { File(target.localPhotoPath).delete() }
        }
        val updated = _employees.value.filterNot { it.id == employeeId }
        _employees.value = updated
        persistEmployees(updated)
        markEmployeeSynced(employeeId)
        if (queueCloudDelete) {
            val pendingDeletes = _pendingDeleteEmployeeIds.value + employeeId
            _pendingDeleteEmployeeIds.value = pendingDeletes
            persistStringSet(KEY_PENDING_DELETE_EMPLOYEE_IDS, pendingDeletes)
        }
    }

    fun recordLocalAttendance(
        employee: Employee,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        loggedTimeLabel: String,
        loggedDateKey: String,
        synced: Boolean,
        explicitLogId: String? = null,
        gpsCoordinates: String = "",
        locationAddress: String = "",
        timeSource: String = ""
    ): AttendanceLog {
        val logId = explicitLogId ?: "log_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val entry = AttendanceLog(
            id = logId,
            employeeId = employee.id,
            employeeCode = employee.employeeCode,
            employeeName = employee.name,
            department = employee.department,
            avatarKey = employee.avatarKey,
            eventType = eventType.wireValue,
            verificationMethod = verificationMethod.wireValue,
            confidenceScore = confidenceScore.toLong().coerceIn(0L, 100L),
            loggedTimeLabel = loggedTimeLabel,
            loggedDateKey = loggedDateKey,
            gpsCoordinates = gpsCoordinates,
            locationAddress = locationAddress,
            timeSource = timeSource,
            createdAt = Timestamp.now()
        )
        val updatedLogs = (listOf(entry) + _logs.value.filterNot { it.id == logId }).take(MAX_LOCAL_LOGS)
        _logs.value = updatedLogs
        persistLogs(updatedLogs)

        if (!synced) {
            val updatedPending = _pendingSyncLogIds.value + logId
            _pendingSyncLogIds.value = updatedPending
            persistStringSet(KEY_PENDING_SYNC_IDS, updatedPending)
        }
        return entry
    }

    fun persistSelfieForLog(logId: String, sourcePath: String?): String? {
        if (logId.isBlank() || sourcePath.isNullOrBlank()) return null
        val srcFile = File(sourcePath)
        if (!srcFile.exists() || srcFile.length() <= 0L) return null

        val storedPath = runCatching {
            val dir = File(appContext.filesDir, "weekly_selfies").apply {
                if (!exists()) mkdirs()
            }
            val safeId = logId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val destFile = File(dir, "$safeId.jpg")
            if (srcFile.absolutePath != destFile.absolutePath) {
                srcFile.copyTo(destFile, overwrite = true)
            }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_STORED_SELFIES)?.forEach { old ->
                runCatching { old.delete() }
            }
            destFile.absolutePath
        }.getOrElse { srcFile.absolutePath }

        val map = getSelfiePathsByLogId().toMutableMap()
        map[logId] = storedPath
        persistSelfiePathsMap(map)
        return storedPath
    }

    fun getSelfiePathsByLogId(): Map<String, String> {
        val raw = prefs.getString(KEY_LOG_SELFIES_JSON, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                if (v.isNotBlank()) {
                    result[k] = v
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistSelfiePathsMap(map: Map<String, String>) {
        val trimmedEntries = map.entries
            .filter { File(it.value).exists() }
            .toList()
            .takeLast(MAX_STORED_SELFIES)
        val obj = JSONObject()
        trimmedEntries.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit { putString(KEY_LOG_SELFIES_JSON, obj.toString()) }
    }

    fun markLogSynced(logId: String) {
        if (logId !in _pendingSyncLogIds.value) return
        val updated = _pendingSyncLogIds.value - logId
        _pendingSyncLogIds.value = updated
        persistStringSet(KEY_PENDING_SYNC_IDS, updated)
    }

    fun markEmployeePendingSync(employeeId: String) {
        if (employeeId in _pendingSyncEmployeeIds.value) return
        val updated = _pendingSyncEmployeeIds.value + employeeId
        _pendingSyncEmployeeIds.value = updated
        persistStringSet(KEY_PENDING_EMPLOYEE_IDS, updated)
    }

    fun markEmployeeSynced(employeeId: String) {
        if (employeeId !in _pendingSyncEmployeeIds.value) return
        val updated = _pendingSyncEmployeeIds.value - employeeId
        _pendingSyncEmployeeIds.value = updated
        persistStringSet(KEY_PENDING_EMPLOYEE_IDS, updated)
    }

    fun markEmployeeDeleteSynced(employeeId: String) {
        if (employeeId !in _pendingDeleteEmployeeIds.value) return
        val updated = _pendingDeleteEmployeeIds.value - employeeId
        _pendingDeleteEmployeeIds.value = updated
        persistStringSet(KEY_PENDING_DELETE_EMPLOYEE_IDS, updated)
    }

    fun markEmailPending(logId: String) {
        if (logId in _pendingEmailLogIds.value) return
        val updated = _pendingEmailLogIds.value + logId
        _pendingEmailLogIds.value = updated
        persistStringSetSync(KEY_PENDING_EMAIL_LOG_IDS, updated)
    }

    fun markEmailSent(logId: String) {
        if (logId in _pendingEmailLogIds.value) {
            val updated = _pendingEmailLogIds.value - logId
            _pendingEmailLogIds.value = updated
            persistStringSetSync(KEY_PENDING_EMAIL_LOG_IDS, updated)
        }
        if (_pendingCheckOutLock.value?.logId == logId) {
            clearPendingCheckOutLock(logId)
        }
    }

    fun savePendingCheckOutLock(lock: PendingCheckOutLock) {
        _pendingCheckOutLock.value = lock
        persistPendingCheckOutLock(lock)
        markEmailPending(lock.logId)
    }

    fun updatePendingCheckOutPdfPath(logId: String, pdfReportPath: String) {
        val current = _pendingCheckOutLock.value ?: return
        if (current.logId != logId || pdfReportPath.isBlank()) return
        val updated = current.copy(pdfReportPath = pdfReportPath)
        _pendingCheckOutLock.value = updated
        persistPendingCheckOutLock(updated)
    }

    fun clearPendingCheckOutLock(logId: String? = null) {
        val current = _pendingCheckOutLock.value
        if (current != null && (logId == null || current.logId == logId)) {
            _pendingCheckOutLock.value = null
            prefs.edit(commit = true) { remove(KEY_PENDING_CHECKOUT_LOCK_JSON) }
        }
        val idToClear = logId ?: current?.logId
        if (!idToClear.isNullOrBlank() && idToClear in _pendingEmailLogIds.value) {
            val updated = _pendingEmailLogIds.value - idToClear
            _pendingEmailLogIds.value = updated
            persistStringSetSync(KEY_PENDING_EMAIL_LOG_IDS, updated)
        }
    }

    fun markServerEmployeePending(employeeId: String) {
        if (employeeId.isBlank() || employeeId in _pendingServerEmployeeIds.value) return
        val updated = _pendingServerEmployeeIds.value + employeeId
        _pendingServerEmployeeIds.value = updated
        persistStringSet(KEY_PENDING_SERVER_EMPLOYEE_IDS, updated)
    }

    fun markServerEmployeeSynced(employeeId: String) {
        if (employeeId !in _pendingServerEmployeeIds.value) return
        val updated = _pendingServerEmployeeIds.value - employeeId
        _pendingServerEmployeeIds.value = updated
        persistStringSet(KEY_PENDING_SERVER_EMPLOYEE_IDS, updated)
    }

    fun markServerLogPending(
        logId: String,
        ntpEpochMillis: Long = 0L,
        emailDispatchStatus: String = ""
    ) {
        if (logId.isBlank()) return
        if (ntpEpochMillis > 0L || emailDispatchStatus.isNotBlank()) {
            val map = loadServerMetadataMap().toMutableMap()
            val prev = map[logId] ?: ServerLogMetadata()
            map[logId] = ServerLogMetadata(
                ntpEpochMillis = if (ntpEpochMillis > 0L) ntpEpochMillis else prev.ntpEpochMillis,
                emailDispatchStatus = emailDispatchStatus.ifBlank { prev.emailDispatchStatus }
            )
            persistServerMetadataMap(map)
        }
        if (logId !in _pendingServerLogIds.value) {
            val updated = _pendingServerLogIds.value + logId
            _pendingServerLogIds.value = updated
            persistStringSet(KEY_PENDING_SERVER_LOG_IDS, updated)
        }
    }

    fun updateServerLogEmailStatus(logId: String, emailDispatchStatus: String) {
        if (logId.isBlank() || emailDispatchStatus.isBlank()) return
        markServerLogPending(logId = logId, emailDispatchStatus = emailDispatchStatus)
    }

    fun markServerLogSynced(logId: String) {
        if (logId !in _pendingServerLogIds.value) return
        val updated = _pendingServerLogIds.value - logId
        _pendingServerLogIds.value = updated
        persistStringSet(KEY_PENDING_SERVER_LOG_IDS, updated)
    }

    fun getServerLogMetadata(logId: String): ServerLogMetadata {
        return loadServerMetadataMap()[logId] ?: ServerLogMetadata()
    }

    fun enqueueServerEmailReport(report: PendingServerEmailReport) {
        if (report.reportId.isBlank()) return
        val current = getPendingServerEmailReports()
            .filterNot { it.reportId == report.reportId }
        val updated = (current + report).takeLast(MAX_LOCAL_LOGS)
        persistPendingServerEmailReports(updated)
    }

    fun getPendingServerEmailReports(): List<PendingServerEmailReport> {
        val raw = prefs.getString(KEY_PENDING_SERVER_EMAIL_REPORTS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                PendingServerEmailReport(
                    reportId = obj.optString("reportId"),
                    logId = obj.optString("logId"),
                    employeeId = obj.optString("employeeId"),
                    employeeCode = obj.optString("employeeCode"),
                    employeeName = obj.optString("employeeName"),
                    department = obj.optString("department"),
                    eventType = obj.optString("eventType"),
                    recipientEmail = obj.optString("recipientEmail"),
                    dispatchStatus = obj.optString("dispatchStatus"),
                    pdfFileName = obj.optString("pdfFileName"),
                    gpsCoordinates = obj.optString("gpsCoordinates"),
                    locationAddress = obj.optString("locationAddress"),
                    timeSource = obj.optString("timeSource"),
                    ntpEpochMillis = obj.optLong("ntpEpochMillis", 0L),
                    dispatchedAtEpochMs = obj.optLong("dispatchedAtEpochMs", System.currentTimeMillis())
                )
            }.filter { it.reportId.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun markServerEmailReportSynced(reportId: String) {
        val remaining = getPendingServerEmailReports().filterNot { it.reportId == reportId }
        persistPendingServerEmailReports(remaining)
    }

    private fun loadServerMetadataMap(): Map<String, ServerLogMetadata> {
        val raw = prefs.getString(KEY_LOG_SERVER_METADATA_JSON, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, ServerLogMetadata>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val inner = obj.optJSONObject(k) ?: continue
                result[k] = ServerLogMetadata(
                    ntpEpochMillis = inner.optLong("ntpEpochMillis", 0L),
                    emailDispatchStatus = inner.optString("emailDispatchStatus")
                )
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistServerMetadataMap(map: Map<String, ServerLogMetadata>) {
        val obj = JSONObject()
        map.entries.toList().takeLast(MAX_LOCAL_LOGS).forEach { (k, v) ->
            obj.put(
                k,
                JSONObject().apply {
                    put("ntpEpochMillis", v.ntpEpochMillis)
                    put("emailDispatchStatus", v.emailDispatchStatus)
                }
            )
        }
        prefs.edit { putString(KEY_LOG_SERVER_METADATA_JSON, obj.toString()) }
    }

    private fun persistPendingServerEmailReports(list: List<PendingServerEmailReport>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("reportId", r.reportId)
                    put("logId", r.logId)
                    put("employeeId", r.employeeId)
                    put("employeeCode", r.employeeCode)
                    put("employeeName", r.employeeName)
                    put("department", r.department)
                    put("eventType", r.eventType)
                    put("recipientEmail", r.recipientEmail)
                    put("dispatchStatus", r.dispatchStatus)
                    put("pdfFileName", r.pdfFileName)
                    put("gpsCoordinates", r.gpsCoordinates)
                    put("locationAddress", r.locationAddress)
                    put("timeSource", r.timeSource)
                    put("ntpEpochMillis", r.ntpEpochMillis)
                    put("dispatchedAtEpochMs", r.dispatchedAtEpochMs)
                }
            )
        }
        prefs.edit { putString(KEY_PENDING_SERVER_EMAIL_REPORTS_JSON, arr.toString()) }
    }

    fun mergeRemoteEmployees(remoteEmployees: List<Employee>) {
        if (remoteEmployees.isEmpty()) return
        val pendingEdits = _pendingSyncEmployeeIds.value
        val pendingDeletes = _pendingDeleteEmployeeIds.value
        val byId = _employees.value.associateBy { it.id }.toMutableMap()
        remoteEmployees.forEach { remote ->
            if (remote.id !in pendingDeletes && remote.id !in pendingEdits) {
                // Preserve localPhotoPath stored on this device
                val localPhoto = byId[remote.id]?.localPhotoPath.orEmpty()
                byId[remote.id] = remote.copy(localPhotoPath = localPhoto)
            }
        }
        val merged = byId.values.filterNot { it.id in pendingDeletes }.sortedBy { it.employeeCode }
        _employees.value = merged
        persistEmployees(merged)
    }

    fun mergeRemoteLogs(remoteLogs: List<AttendanceLog>) {
        val pendingIds = _pendingSyncLogIds.value
        val mergedById = LinkedHashMap<String, AttendanceLog>()
        _logs.value.forEach { mergedById[it.id] = it }
        remoteLogs.forEach { remote ->
            if (remote.id !in pendingIds) {
                mergedById[remote.id] = remote
            }
        }
        val sorted = mergedById.values
            .sortedWith(
                compareByDescending<AttendanceLog> { it.createdAt?.seconds ?: 0L }
                    .thenByDescending { it.loggedDateKey }
            )
            .take(MAX_LOCAL_LOGS)
        _logs.value = sorted
        persistLogs(sorted)
    }

    private fun loadOrSeedEmployees(): List<Employee> {
        val raw = prefs.getString(KEY_EMPLOYEES, null)
        val parsed = if (!raw.isNullOrBlank()) parseEmployees(raw) else emptyList()
        val hasAnsar = parsed.any { it.id == MUHAMMAD_ANSAR_ID }
        if (parsed.isNotEmpty() && hasAnsar) return parsed

        val seeded = (defaultPreRegisteredEmployees() + parsed.filterNot { it.id == MUHAMMAD_ANSAR_ID })
            .distinctBy { it.id }
        persistEmployees(seeded)
        return seeded
    }

    private fun parseEmployees(raw: String): List<Employee> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                Employee(
                    id = obj.optString("id"),
                    userId = obj.optString("userId"),
                    employeeCode = obj.optString("employeeCode"),
                    name = obj.optString("name"),
                    department = obj.optString("department"),
                    roleTitle = obj.optString("roleTitle"),
                    passcode = obj.optString("passcode"),
                    checkInCode = obj.optString("checkInCode"),
                    checkOutCode = obj.optString("checkOutCode"),
                    avatarKey = obj.optString("avatarKey", "avatar_1"),
                    faceSignature = obj.optString("faceSignature"),
                    isActive = obj.optBoolean("isActive", true),
                    localPhotoPath = obj.optString("localPhotoPath")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistEmployees(list: List<Employee>) {
        val arr = JSONArray()
        list.forEach { emp ->
            arr.put(
                JSONObject().apply {
                    put("id", emp.id)
                    put("userId", emp.userId)
                    put("employeeCode", emp.employeeCode)
                    put("name", emp.name)
                    put("department", emp.department)
                    put("roleTitle", emp.roleTitle)
                    put("passcode", emp.passcode)
                    put("checkInCode", emp.checkInCode)
                    put("checkOutCode", emp.checkOutCode)
                    put("avatarKey", emp.avatarKey)
                    put("faceSignature", emp.faceSignature)
                    put("isActive", emp.isActive)
                    put("localPhotoPath", emp.localPhotoPath)
                }
            )
        }
        prefs.edit { putString(KEY_EMPLOYEES, arr.toString()) }
    }

    private fun loadLogs(): List<AttendanceLog> {
        val raw = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val obj = arr.getJSONObject(idx)
                val epochSec = obj.optLong("createdAtSec", 0L)
                AttendanceLog(
                    id = obj.optString("id"),
                    userId = obj.optString("userId"),
                    employeeId = obj.optString("employeeId"),
                    employeeCode = obj.optString("employeeCode"),
                    employeeName = obj.optString("employeeName"),
                    department = obj.optString("department"),
                    avatarKey = obj.optString("avatarKey", "avatar_1"),
                    eventType = obj.optString("eventType", AttendanceEventType.CHECK_IN.wireValue),
                    verificationMethod = obj.optString("verificationMethod", VerificationMethod.PASSCODE.wireValue),
                    confidenceScore = obj.optLong("confidenceScore", 100L),
                    loggedTimeLabel = obj.optString("loggedTimeLabel", AttendanceTimeRules.formatTimeLabel()),
                    loggedDateKey = obj.optString("loggedDateKey", AttendanceTimeRules.formatDateKey()),
                    gpsCoordinates = obj.optString("gpsCoordinates"),
                    locationAddress = obj.optString("locationAddress"),
                    timeSource = obj.optString("timeSource"),
                    createdAt = if (epochSec > 0L) Timestamp(epochSec, 0) else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistLogs(list: List<AttendanceLog>) {
        val arr = JSONArray()
        list.forEach { log ->
            arr.put(
                JSONObject().apply {
                    put("id", log.id)
                    put("userId", log.userId)
                    put("employeeId", log.employeeId)
                    put("employeeCode", log.employeeCode)
                    put("employeeName", log.employeeName)
                    put("department", log.department)
                    put("avatarKey", log.avatarKey)
                    put("eventType", log.eventType)
                    put("verificationMethod", log.verificationMethod)
                    put("confidenceScore", log.confidenceScore)
                    put("loggedTimeLabel", log.loggedTimeLabel)
                    put("loggedDateKey", log.loggedDateKey)
                    put("gpsCoordinates", log.gpsCoordinates)
                    put("locationAddress", log.locationAddress)
                    put("timeSource", log.timeSource)
                    put("createdAtSec", log.createdAt?.seconds ?: (System.currentTimeMillis() / 1000L))
                }
            )
        }
        prefs.edit { putString(KEY_LOGS, arr.toString()) }
    }

    private fun loadPendingCheckOutLock(): PendingCheckOutLock? {
        val raw = prefs.getString(KEY_PENDING_CHECKOUT_LOCK_JSON, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val logId = obj.optString("logId")
            val empName = obj.optString("employeeName")
            if (logId.isBlank() || empName.isBlank()) return null
            PendingCheckOutLock(
                logId = logId,
                employeeId = obj.optString("employeeId"),
                employeeCode = obj.optString("employeeCode"),
                employeeName = empName,
                department = obj.optString("department", "Authorized Staff"),
                roleTitle = obj.optString("roleTitle", "Authorized Staff"),
                avatarKey = obj.optString("avatarKey", "avatar_1"),
                localEmployeePhotoPath = obj.optString("localEmployeePhotoPath"),
                verificationMethod = obj.optString("verificationMethod", VerificationMethod.FACE_SCAN_1.wireValue),
                confidenceScore = obj.optInt("confidenceScore", 90),
                loggedTimeLabel = obj.optString("loggedTimeLabel"),
                loggedDateKey = obj.optString("loggedDateKey"),
                gpsCoordinates = obj.optString("gpsCoordinates"),
                locationAddress = obj.optString("locationAddress"),
                timeSource = obj.optString("timeSource"),
                liveSelfiePath = obj.optString("liveSelfiePath"),
                pdfReportPath = obj.optString("pdfReportPath"),
                lockedAtMillis = obj.optLong("lockedAtMillis", System.currentTimeMillis())
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun persistPendingCheckOutLock(lock: PendingCheckOutLock) {
        val obj = JSONObject().apply {
            put("logId", lock.logId)
            put("employeeId", lock.employeeId)
            put("employeeCode", lock.employeeCode)
            put("employeeName", lock.employeeName)
            put("department", lock.department)
            put("roleTitle", lock.roleTitle)
            put("avatarKey", lock.avatarKey)
            put("localEmployeePhotoPath", lock.localEmployeePhotoPath)
            put("verificationMethod", lock.verificationMethod)
            put("confidenceScore", lock.confidenceScore)
            put("loggedTimeLabel", lock.loggedTimeLabel)
            put("loggedDateKey", lock.loggedDateKey)
            put("gpsCoordinates", lock.gpsCoordinates)
            put("locationAddress", lock.locationAddress)
            put("timeSource", lock.timeSource)
            put("liveSelfiePath", lock.liveSelfiePath)
            put("pdfReportPath", lock.pdfReportPath)
            put("lockedAtMillis", lock.lockedAtMillis)
        }
        prefs.edit(commit = true) { putString(KEY_PENDING_CHECKOUT_LOCK_JSON, obj.toString()) }
    }

    private fun loadStringSet(key: String): Set<String> {
        return prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun persistStringSet(key: String, ids: Set<String>) {
        prefs.edit { putStringSet(key, ids) }
    }

    private fun persistStringSetSync(key: String, ids: Set<String>) {
        prefs.edit(commit = true) { putStringSet(key, ids) }
    }

    companion object {
        private const val TAG = "OfflineStore"
        private const val PREFS_NAME = "nich_offline_attendance"
        private const val KEY_EMPLOYEES = "employees_json"
        private const val KEY_LOGS = "logs_json"
        private const val KEY_PENDING_SYNC_IDS = "pending_sync_ids"
        private const val KEY_PENDING_EMPLOYEE_IDS = "pending_employee_ids"
        private const val KEY_PENDING_DELETE_EMPLOYEE_IDS = "pending_delete_employee_ids"
        private const val KEY_PENDING_EMAIL_LOG_IDS = "pending_email_log_ids"
        private const val KEY_PENDING_CHECKOUT_LOCK_JSON = "pending_checkout_lock_json"
        private const val KEY_PENDING_SERVER_LOG_IDS = "pending_server_log_ids"
        private const val KEY_PENDING_SERVER_EMPLOYEE_IDS = "pending_server_employee_ids"
        private const val KEY_PENDING_SERVER_EMAIL_REPORTS_JSON = "pending_server_email_reports_json"
        private const val KEY_LOG_SERVER_METADATA_JSON = "log_server_metadata_json"
        private const val KEY_LOG_SELFIES_JSON = "log_selfies_json"
        private const val KEY_REGISTRATION_COMPLETED = "local_registration_completed"
        private const val MAX_LOCAL_LOGS = 400
        private const val MAX_STORED_SELFIES = 80

        const val MUHAMMAD_ANSAR_ID = "emp_muhammad_ansar"
        private const val LEGACY_ANSAR_FACE_SIGNATURE = "0.4350:0.4800:0.6400:0.7500"
        const val DEFAULT_ANSAR_FACE_SIGNATURE =
            "0.4350:0.4800:0.6400:0.7500:0.2800:0.7600:0.7300:0.9500"

        fun defaultPreRegisteredEmployees(): List<Employee> = listOf(
            Employee(
                id = MUHAMMAD_ANSAR_ID,
                employeeCode = "NICH-0147",
                name = "محمد انصار (Muhammad Ansar)",
                department = "Executive Operations",
                roleTitle = "Authorized Staff",
                passcode = "147",
                checkInCode = "147",
                checkOutCode = "369",
                avatarKey = "avatar_ansar",
                faceSignature = "",
                isActive = true
            ),
            Employee(
                id = "emp_nich_1042",
                employeeCode = "NICH-1042",
                name = "Dr. Ananya Rao",
                department = "Biometric Security",
                roleTitle = "VP Engineering",
                passcode = "1042",
                avatarKey = "avatar_1",
                faceSignature = "",
                isActive = true
            ),
            Employee(
                id = "emp_nich_2088",
                employeeCode = "NICH-2088",
                name = "Marcus Chen",
                department = "Neural Systems",
                roleTitle = "Principal Architect",
                passcode = "2088",
                avatarKey = "avatar_2",
                faceSignature = "",
                isActive = true
            ),
            Employee(
                id = "emp_nich_3095",
                employeeCode = "NICH-3095",
                name = "Elena Vance",
                department = "Operations Command",
                roleTitle = "Security Director",
                passcode = "3095",
                avatarKey = "avatar_3",
                faceSignature = "",
                isActive = true
            )
        )
    }
}
