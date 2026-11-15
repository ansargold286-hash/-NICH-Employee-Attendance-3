package com.example.data.local

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.edit
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.AttendanceTimeRules
import com.example.data.model.Employee
import com.example.data.model.FaceBiometricMatcher
import com.example.data.model.PasscodeMatchResult
import com.example.data.model.PasscodeResolver
import com.example.data.model.VerificationMethod
import com.example.ui.components.extractFaceSignature
import com.google.firebase.Timestamp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

    /**
     * Extracts ML Kit facial landmarks directly from the bundled Muhammad Ansar reference image
     * so live camera scans use the exact same landmark geometry space.
     */
    suspend fun calibrateRegisteredReferenceFaces() {
        val current = _employees.value
        val ansar = current.firstOrNull { it.id == MUHAMMAD_ANSAR_ID } ?: return
        val existingDim = FaceBiometricMatcher.parseSignature(ansar.faceSignature)?.size ?: 0
        if (ansar.faceSignature != DEFAULT_ANSAR_FACE_SIGNATURE &&
            ansar.faceSignature != LEGACY_ANSAR_FACE_SIGNATURE &&
            existingDim >= FaceBiometricMatcher.FULL_FEATURE_DIMENSION
        ) {
            return
        }
        try {
            val extracted = withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeResource(
                    appContext.resources,
                    R.drawable.emp_muhammad_ansar
                ) ?: return@withContext null
                val options = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setMinFaceSize(0.15f)
                    .build()
                val detector = FaceDetection.getClient(options)
                val faces = try {
                    detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                } finally {
                    detector.close()
                }
                val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                primaryFace?.let { extractFaceSignature(it) }
            }
            if (!extracted.isNullOrBlank()) {
                val latest = _employees.value
                val updated = latest.map { emp ->
                    if (emp.id == MUHAMMAD_ANSAR_ID) emp.copy(faceSignature = extracted) else emp
                }
                _employees.value = updated
                persistEmployees(updated)
                markEmployeePendingSync(MUHAMMAD_ANSAR_ID)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reference face extraction fallback active: ${e.message}")
        }
    }

    fun verifyCode(rawCode: String, defaultEventType: AttendanceEventType): PasscodeMatchResult? {
        return PasscodeResolver.resolve(rawCode, _employees.value, defaultEventType)
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
        faceSignature: String = ""
    ): Employee {
        val newEmployee = Employee(
            id = "emp_${UUID.randomUUID().toString().replace("-", "").take(16)}",
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
            updatedAt = Timestamp.now()
        )
        val updated = (_employees.value + newEmployee).sortedBy { it.employeeCode }
        _employees.value = updated
        persistEmployees(updated)
        markEmployeePendingSync(newEmployee.id)
        return newEmployee
    }

    fun updateEmployeeFaceSignature(employee: Employee, signature: String) {
        val updated = _employees.value.map {
            if (it.id == employee.id) it.copy(faceSignature = signature.trim(), updatedAt = Timestamp.now()) else it
        }
        _employees.value = updated
        persistEmployees(updated)
        markEmployeePendingSync(employee.id)
    }

    fun deleteEmployee(employeeId: String, queueCloudDelete: Boolean = true) {
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
        explicitLogId: String? = null
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
            createdAt = Timestamp.now()
        )
        val updatedLogs = (listOf(entry) + _logs.value.filterNot { it.id == logId }).take(150)
        _logs.value = updatedLogs
        persistLogs(updatedLogs)

        if (!synced) {
            val updatedPending = _pendingSyncLogIds.value + logId
            _pendingSyncLogIds.value = updatedPending
            persistStringSet(KEY_PENDING_SYNC_IDS, updatedPending)
        }
        return entry
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
        persistStringSet(KEY_PENDING_EMAIL_LOG_IDS, updated)
    }

    fun markEmailSent(logId: String) {
        if (logId !in _pendingEmailLogIds.value) return
        val updated = _pendingEmailLogIds.value - logId
        _pendingEmailLogIds.value = updated
        persistStringSet(KEY_PENDING_EMAIL_LOG_IDS, updated)
    }

    fun mergeRemoteEmployees(remoteEmployees: List<Employee>) {
        if (remoteEmployees.isEmpty()) return
        val pendingEdits = _pendingSyncEmployeeIds.value
        val pendingDeletes = _pendingDeleteEmployeeIds.value
        val byId = _employees.value.associateBy { it.id }.toMutableMap()
        remoteEmployees.forEach { remote ->
            if (remote.id !in pendingDeletes && remote.id !in pendingEdits) {
                byId[remote.id] = remote
            }
        }
        val merged = byId.values.filterNot { it.id in pendingDeletes }.sortedBy { it.employeeCode }
        _employees.value = merged
        persistEmployees(merged)
    }

    fun mergeRemoteLogs(remoteLogs: List<AttendanceLog>) {
        val pendingIds = _pendingSyncLogIds.value
        val pendingLocalLogs = _logs.value.filter { it.id in pendingIds }
        val mergedById = LinkedHashMap<String, AttendanceLog>()
        pendingLocalLogs.forEach { mergedById[it.id] = it }
        remoteLogs.forEach { mergedById[it.id] = it }
        val sorted = mergedById.values.sortedByDescending { it.createdAt?.seconds ?: 0L }
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
                    isActive = obj.optBoolean("isActive", true)
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
                    put("createdAtSec", log.createdAt?.seconds ?: (System.currentTimeMillis() / 1000L))
                }
            )
        }
        prefs.edit { putString(KEY_LOGS, arr.toString()) }
    }

    private fun loadStringSet(key: String): Set<String> {
        return prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    }

    private fun persistStringSet(key: String, ids: Set<String>) {
        prefs.edit { putStringSet(key, ids) }
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

        const val MUHAMMAD_ANSAR_ID = "emp_muhammad_ansar"
        private const val LEGACY_ANSAR_FACE_SIGNATURE = "0.4350:0.4800:0.6400:0.7500"
        // Full 8-feature baseline landmark vector (eyes, nose, mouth, aspect, philtrum/mustache, beard/jaw drop, jaw/cheek, symmetry)
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
                faceSignature = DEFAULT_ANSAR_FACE_SIGNATURE,
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
