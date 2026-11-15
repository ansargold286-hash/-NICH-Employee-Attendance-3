package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceLog
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class AttendanceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = FirebaseAuth.getInstance()
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Terminal operator must sign in with Google before accessing Firestore.")
    }

    private fun employeesCollection(userId: String) =
        db.collection("users").document(userId).collection("employees")

    private fun attendanceLogsCollection(userId: String) =
        db.collection("users").document(userId).collection("attendanceLogs")

    fun observeEmployees(userId: String): Flow<List<Employee>> = flow {
        val col = employeesCollection(userId)
        emitAll(
            col.orderBy("employeeCode", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Employee::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, col.path)
                    }
                    throw error
                }
        )
    }

    fun observeAttendanceLogs(userId: String): Flow<List<AttendanceLog>> = flow {
        val col = attendanceLogsCollection(userId)
        emitAll(
            col.orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        AttendanceLog::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, col.path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureStarterDirectoryIfEmpty(
        starterEmployees: List<Employee>
    ): Result<Boolean> {
        val uid = requireUserId()
        val col = employeesCollection(uid)
        return try {
            val existing = col.limit(1).get().await()
            if (!existing.isEmpty) {
                // Ensure Muhammad Ansar pre-registered profile is present for admin sync
                val ansar = starterEmployees.firstOrNull { it.id == "emp_muhammad_ansar" }
                if (ansar != null) {
                    val ansarSnap = col.document(ansar.id).get().await()
                    if (!ansarSnap.exists()) {
                        col.document(ansar.id).set(buildEmployeePayload(uid, ansar)).await()
                    }
                }
                return Result.success(false)
            }
            for (emp in starterEmployees) {
                col.document(emp.id).set(buildEmployeePayload(uid, emp)).await()
            }
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, col.path)
            Result.failure(e)
        }
    }

    suspend fun upsertEmployee(
        employee: Employee,
        overwriteIfExists: Boolean = false
    ): Result<Unit> {
        val uid = requireUserId()
        require(employee.id.isNotBlank()) { "Employee ID must not be blank" }
        val docRef = employeesCollection(uid).document(employee.id)
        return try {
            val existing = docRef.get().await()
            if (!existing.exists()) {
                docRef.set(buildEmployeePayload(uid, employee)).await()
            } else if (overwriteIfExists) {
                val updates = mapOf(
                    "employeeCode" to employee.employeeCode.trim(),
                    "name" to employee.name.trim(),
                    "department" to employee.department.trim(),
                    "roleTitle" to employee.roleTitle.trim(),
                    "passcode" to employee.passcode.trim(),
                    "checkInCode" to employee.checkInCode.trim(),
                    "checkOutCode" to employee.checkOutCode.trim(),
                    "avatarKey" to employee.avatarKey,
                    "faceSignature" to employee.faceSignature.trim(),
                    "isActive" to employee.isActive,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (overwriteIfExists) OperationType.UPDATE else OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    private fun buildEmployeePayload(uid: String, emp: Employee): Map<String, Any> = mapOf(
        "userId" to uid,
        "employeeCode" to emp.employeeCode.trim(),
        "name" to emp.name.trim(),
        "department" to emp.department.trim(),
        "roleTitle" to emp.roleTitle.trim(),
        "passcode" to emp.passcode.trim(),
        "checkInCode" to emp.checkInCode.trim(),
        "checkOutCode" to emp.checkOutCode.trim(),
        "avatarKey" to emp.avatarKey,
        "faceSignature" to emp.faceSignature.trim(),
        "isActive" to emp.isActive,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    suspend fun createEmployee(
        employeeCode: String,
        name: String,
        department: String,
        roleTitle: String,
        passcode: String,
        avatarKey: String,
        faceSignature: String = "",
        checkInCode: String = "",
        checkOutCode: String = "",
        explicitDocId: String? = null
    ): Result<String> {
        val uid = requireUserId()
        val docId = explicitDocId ?: "emp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = employeesCollection(uid).document(docId)
        val payload = mapOf(
            "userId" to uid,
            "employeeCode" to employeeCode.trim(),
            "name" to name.trim(),
            "department" to department.trim(),
            "roleTitle" to roleTitle.trim(),
            "passcode" to passcode.trim(),
            "checkInCode" to checkInCode.trim(),
            "checkOutCode" to checkOutCode.trim(),
            "avatarKey" to avatarKey,
            "faceSignature" to faceSignature.trim(),
            "isActive" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateEmployee(employee: Employee): Result<Unit> {
        val uid = requireUserId()
        require(employee.id.isNotBlank()) { "Employee ID must not be blank for update" }
        val docRef = employeesCollection(uid).document(employee.id)
        val updates = mapOf(
            "employeeCode" to employee.employeeCode.trim(),
            "name" to employee.name.trim(),
            "department" to employee.department.trim(),
            "roleTitle" to employee.roleTitle.trim(),
            "passcode" to employee.passcode.trim(),
            "checkInCode" to employee.checkInCode.trim(),
            "checkOutCode" to employee.checkOutCode.trim(),
            "avatarKey" to employee.avatarKey,
            "faceSignature" to employee.faceSignature.trim(),
            "isActive" to employee.isActive,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteEmployee(employeeId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = employeesCollection(uid).document(employeeId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun verifyEmployeeByPasscode(passcode: String): Result<Employee?> {
        val uid = requireUserId()
        val col = employeesCollection(uid)
        val normalized = passcode.trim()
        return try {
            val checkInMatch = col
                .whereEqualTo("checkInCode", normalized)
                .whereEqualTo("isActive", true)
                .limit(1)
                .get()
                .await()
                .documents
                .firstOrNull()
                ?.toObject(Employee::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            if (checkInMatch != null) return Result.success(checkInMatch)

            val checkOutMatch = col
                .whereEqualTo("checkOutCode", normalized)
                .whereEqualTo("isActive", true)
                .limit(1)
                .get()
                .await()
                .documents
                .firstOrNull()
                ?.toObject(Employee::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            if (checkOutMatch != null) return Result.success(checkOutMatch)

            val snapshot = col
                .whereEqualTo("passcode", normalized)
                .whereEqualTo("isActive", true)
                .limit(1)
                .get()
                .await()
            val match = snapshot.documents.firstOrNull()?.toObject(
                Employee::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(match)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, col.path)
            Result.failure(e)
        }
    }

    suspend fun getEmployeeById(employeeId: String): Result<Employee?> {
        val uid = requireUserId()
        val docRef = employeesCollection(uid).document(employeeId)
        return try {
            val snapshot = docRef.get().await()
            val emp = snapshot.toObject(
                Employee::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(emp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun logAttendance(
        employee: Employee,
        eventType: AttendanceEventType,
        verificationMethod: VerificationMethod,
        confidenceScore: Int,
        loggedTimeLabel: String,
        loggedDateKey: String,
        explicitLogId: String? = null
    ): Result<String> {
        val uid = requireUserId()
        require(employee.id.isNotBlank()) { "Employee ID must not be blank" }
        val logId = explicitLogId ?: "log_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docRef = attendanceLogsCollection(uid).document(logId)
        val payload = mapOf(
            "userId" to uid,
            "employeeId" to employee.id,
            "employeeCode" to employee.employeeCode,
            "employeeName" to employee.name,
            "department" to employee.department,
            "avatarKey" to employee.avatarKey,
            "eventType" to eventType.wireValue,
            "verificationMethod" to verificationMethod.wireValue,
            "confidenceScore" to confidenceScore.coerceIn(0, 100),
            "loggedTimeLabel" to loggedTimeLabel,
            "loggedDateKey" to loggedDateKey,
            "createdAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(logId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }
}
