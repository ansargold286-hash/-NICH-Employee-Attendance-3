package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.AttendanceEventType
import com.example.data.model.AttendanceTimeRules
import com.example.data.model.VerificationMethod
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class AttendanceRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createEmployeeAndVerifyPasscode_authenticatedOwner_succeeds() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = AttendanceRepository(firestore, auth)
        val uniquePasscode = (100..999).random().toString()
        val uniqueOutCode = (100..999).random().toString()
        val uniqueCode = "NICH-${UUID.randomUUID().toString().take(6)}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createEmployee(
                employeeCode = uniqueCode,
                name = "محمد انصار (Muhammad Ansar)",
                department = "Executive Operations",
                roleTitle = "Authorized Staff",
                passcode = uniquePasscode,
                avatarKey = "avatar_ansar",
                faceSignature = "0.4350:0.4800:0.6400:0.7500",
                checkInCode = uniquePasscode,
                checkOutCode = uniqueOutCode
            )
        }
        assertTrue(createResult.isSuccess)
        val employeeId = createResult.getOrThrow()
        assertTrue(employeeId.isNotBlank())

        val verifiedIn = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.verifyEmployeeByPasscode(uniquePasscode)
        }
        assertTrue(verifiedIn.isSuccess)
        assertEquals("محمد انصار (Muhammad Ansar)", verifiedIn.getOrThrow()?.name)

        val verifiedOut = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.verifyEmployeeByPasscode(uniqueOutCode)
        }
        assertTrue(verifiedOut.isSuccess)
        assertEquals(employeeId, verifiedOut.getOrThrow()?.id)

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeEmployees(uid).first { list -> list.any { it.id == employeeId } }
        }
        assertTrue(emitted.any { it.id == employeeId })
    }

    @Test
    fun logAttendance_validEmployee_createsImmutableLog() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = AttendanceRepository(firestore, auth)

        val employeeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createEmployee(
                employeeCode = "NICH-${UUID.randomUUID().toString().take(6)}",
                name = "Marcus Chen",
                department = "Neural Systems",
                roleTitle = "Principal Architect",
                passcode = (1000..9999).random().toString(),
                avatarKey = "avatar_2"
            ).getOrThrow()
        }
        val employee = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getEmployeeById(employeeId).getOrThrow()!!
        }

        val logResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.logAttendance(
                employee = employee,
                eventType = AttendanceTimeRules.determineEventType(9),
                verificationMethod = VerificationMethod.FACE_SCAN_2,
                confidenceScore = 95,
                loggedTimeLabel = "09:30:00 AM",
                loggedDateKey = "2026-11-13"
            )
        }
        assertTrue(logResult.isSuccess)
        val logId = logResult.getOrThrow()

        val logs = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeAttendanceLogs(uid).first { list -> list.any { it.id == logId } }
        }
        val createdLog = logs.first { it.id == logId }
        assertEquals(AttendanceEventType.CHECK_IN.wireValue, createdLog.eventType)
        assertEquals(VerificationMethod.FACE_SCAN_2.wireValue, createdLog.verificationMethod)
    }

    @Test
    fun getEmployeeById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = AttendanceRepository(firestore, auth)
        val employeeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createEmployee(
                employeeCode = "NICH-CROSS",
                name = "Elena Vance",
                department = "Operations",
                roleTitle = "Director",
                passcode = "3095",
                avatarKey = "avatar_3"
            ).getOrThrow()
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        check(bobUid != aliceUid)

        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(aliceUid)
                    .collection("employees").document(employeeId)
                    .get()
                    .await()
            }
            fail("Expected PERMISSION_DENIED for cross-user read")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    @Test
    fun observeEmployees_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repository = AttendanceRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeEmployees(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED for unauthenticated observeEmployees")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice.nich@test.com"
        const val BOB_EMAIL = "bob.nich@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
