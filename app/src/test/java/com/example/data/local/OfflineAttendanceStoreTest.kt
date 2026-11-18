package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AttendanceEventType
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineAttendanceStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nich_offline_attendance", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun initialSeed_includesMuhammadAnsarWithDualCodes() {
        val store = OfflineAttendanceStore(context)
        val ansar = store.employees.value.firstOrNull { it.id == OfflineAttendanceStore.MUHAMMAD_ANSAR_ID }

        assertNotNull(ansar)
        assertEquals("147", ansar?.checkInCode)
        assertEquals("369", ansar?.checkOutCode)
        assertEquals("avatar_ansar", ansar?.avatarKey)

        val checkInMatch = store.verifyCode("147", AttendanceEventType.CHECK_OUT)
        assertEquals(AttendanceEventType.CHECK_IN, checkInMatch?.eventType)
        assertEquals(OfflineAttendanceStore.MUHAMMAD_ANSAR_ID, checkInMatch?.employee?.id)

        val checkOutMatch = store.verifyCode("369", AttendanceEventType.CHECK_IN)
        assertEquals(AttendanceEventType.CHECK_OUT, checkOutMatch?.eventType)
        assertEquals(OfflineAttendanceStore.MUHAMMAD_ANSAR_ID, checkOutMatch?.employee?.id)
    }

    @Test
    fun offlineAttendanceAndEmployeeEdits_surviveRemoteMergeUntilSynced() {
        val store = OfflineAttendanceStore(context)
        val ansar = store.employees.value.first { it.id == OfflineAttendanceStore.MUHAMMAD_ANSAR_ID }

        val localLog = store.recordLocalAttendance(
            employee = ansar,
            eventType = AttendanceEventType.CHECK_IN,
            verificationMethod = VerificationMethod.PASSCODE,
            confidenceScore = 100,
            loggedTimeLabel = "09:15:00 AM",
            loggedDateKey = "2026-11-13",
            synced = false
        )
        assertTrue(localLog.id in store.pendingSyncLogIds.value)

        val updatedSig = "0.4100:0.4600:0.6200:0.7400"
        store.updateEmployeeFaceSignature(ansar, updatedSig)
        assertTrue(ansar.id in store.pendingSyncEmployeeIds.value)

        // Simulate stale cloud snapshot arriving before pending queue flushes
        val staleRemoteEmployee = ansar.copy(faceSignature = "0.1111:0.2222:0.3333:0.4444")
        store.mergeRemoteEmployees(listOf(staleRemoteEmployee))
        val afterMerge = store.employees.value.first { it.id == ansar.id }
        assertEquals(updatedSig, afterMerge.faceSignature)

        // Simulate empty/older cloud logs arriving while offline log is pending
        store.mergeRemoteLogs(emptyList())
        assertTrue(store.logs.value.any { it.id == localLog.id })

        // Verify persistence across store recreation
        val reloaded = OfflineAttendanceStore(context)
        assertTrue(localLog.id in reloaded.pendingSyncLogIds.value)
        assertTrue(ansar.id in reloaded.pendingSyncEmployeeIds.value)

        reloaded.markLogSynced(localLog.id)
        reloaded.markEmployeeSynced(ansar.id)
        assertFalse(localLog.id in reloaded.pendingSyncLogIds.value)
        assertFalse(ansar.id in reloaded.pendingSyncEmployeeIds.value)
    }

    @Test
    fun offlineDelete_preventsRemoteResurrectionUntilDeleteSynced() {
        val store = OfflineAttendanceStore(context)
        val target = Employee(
            id = "emp_temp_delete",
            employeeCode = "NICH-8888",
            name = "Temp Staff",
            department = "Security",
            roleTitle = "Officer",
            passcode = "8888",
            isActive = true
        )
        store.mergeRemoteEmployees(listOf(target))
        assertTrue(store.employees.value.any { it.id == target.id })

        store.deleteEmployee(target.id, queueCloudDelete = true)
        assertTrue(target.id in store.pendingDeleteEmployeeIds.value)
        assertFalse(store.employees.value.any { it.id == target.id })

        store.mergeRemoteEmployees(listOf(target))
        assertFalse(store.employees.value.any { it.id == target.id })

        store.markEmployeeDeleteSynced(target.id)
        assertFalse(target.id in store.pendingDeleteEmployeeIds.value)
    }

    @Test
    fun registerEmployeesBatch_persistsMultipleNamesPhotosAndEmbeddingsLocally() {
        val store = OfflineAttendanceStore(context)
        assertFalse(store.hasRegisteredFaceProfiles())

        val tempPhoto1 = java.io.File(context.cacheDir, "temp_reg_1.jpg")
        val tempPhoto2 = java.io.File(context.cacheDir, "temp_reg_2.jpg")
        val tempCnic1 = java.io.File(context.cacheDir, "temp_cnic_1.jpg")
        val tempCnic2 = java.io.File(context.cacheDir, "temp_cnic_2.jpg")
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        tempPhoto1.writeBytes(jpegBytes)
        tempPhoto2.writeBytes(jpegBytes)
        tempCnic1.writeBytes(jpegBytes)
        tempCnic2.writeBytes(jpegBytes)

        try {
            val saved = store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Ayesha Khan",
                        department = "Diagnostics",
                        photoPath = tempPhoto1.absolutePath,
                        faceSignature = "0.4200:0.4800:0.6200:0.7400:0.2700:0.7500:0.7100:0.9600",
                        cnicPhotoPath = tempCnic1.absolutePath,
                        cnicNumber = "42101-1111111-1",
                        cnicName = "Ayesha Khan"
                    ),
                    LocalRegistrationInput(
                        name = "Zainab Tariq",
                        department = "Radiology",
                        photoPath = tempPhoto2.absolutePath,
                        faceSignature = "0.3400:0.3800:0.5100:0.8600:0.1900:0.5900:0.8800:0.9100",
                        cnicPhotoPath = tempCnic2.absolutePath,
                        cnicNumber = "42101-2222222-2",
                        cnicName = "Zainab Tariq"
                    )
                )
            )
            assertEquals(2, saved.size)
            assertTrue(store.hasRegisteredFaceProfiles())

            val reloaded = OfflineAttendanceStore(context)
            assertTrue(reloaded.hasRegisteredFaceProfiles())
            val ayesha = reloaded.employees.value.firstOrNull { it.name == "Ayesha Khan" }
            val zainab = reloaded.employees.value.firstOrNull { it.name == "Zainab Tariq" }
            assertNotNull(ayesha)
            assertNotNull(zainab)
            assertTrue(ayesha!!.localPhotoPath.isNotBlank())
            assertTrue(java.io.File(ayesha.localPhotoPath).exists())
            assertTrue(ayesha.cnicPhotoPath.isNotBlank())
            assertTrue(java.io.File(ayesha.cnicPhotoPath).exists())
            assertEquals("42101-1111111-1", ayesha.cnicNumber)
            assertTrue(zainab!!.localPhotoPath.isNotBlank())
            assertTrue(java.io.File(zainab.localPhotoPath).exists())
            assertTrue(zainab.cnicPhotoPath.isNotBlank())
            assertEquals("42101-2222222-2", zainab.cnicNumber)
        } finally {
            tempPhoto1.delete()
            tempPhoto2.delete()
            tempCnic1.delete()
            tempCnic2.delete()
        }
    }

    @Test
    fun getBatchCycleStatus_tracksSingleAndMultiEmployeeDailyCompletion() {
        val store = OfflineAttendanceStore(context)
        val photo1 = java.io.File(context.cacheDir, "batch_cycle_1.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        val photo2 = java.io.File(context.cacheDir, "batch_cycle_2.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        try {
            val enrolled = store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Bilal Ahmed",
                        photoPath = photo1.absolutePath,
                        faceSignature = "0.4200:0.4800:0.6200:0.7400:0.2700:0.7500:0.7100:0.9600"
                    ),
                    LocalRegistrationInput(
                        name = "Sara Ali",
                        photoPath = photo2.absolutePath,
                        faceSignature = "0.3400:0.3800:0.5100:0.8600:0.1900:0.5900:0.8800:0.9100"
                    )
                )
            )
            val emp1 = enrolled[0]
            val emp2 = enrolled[1]
            val dateKey = "2026-11-18"

            store.recordLocalAttendance(emp1, AttendanceEventType.CHECK_IN, VerificationMethod.FACE_SCAN_1, 95, "09:00:00 AM", dateKey, false)
            store.recordLocalAttendance(emp1, AttendanceEventType.CHECK_OUT, VerificationMethod.FACE_SCAN_1, 96, "05:00:00 PM", dateKey, false)
            store.recordLocalAttendance(emp2, AttendanceEventType.CHECK_IN, VerificationMethod.FACE_SCAN_1, 94, "09:05:00 AM", dateKey, false)

            val midStatus = store.getBatchCycleStatus(dateKey)
            assertTrue(midStatus.isMultiEmployeeMode)
            assertEquals(2, midStatus.totalEnrolledCount)
            assertEquals(1, midStatus.completedCount)
            assertFalse(midStatus.allCompleted)
            assertEquals(listOf("Sara Ali"), midStatus.incompleteEmployeeNames)

            store.recordLocalAttendance(emp2, AttendanceEventType.CHECK_OUT, VerificationMethod.FACE_SCAN_1, 97, "05:10:00 PM", dateKey, false)
            val doneStatus = store.getBatchCycleStatus(dateKey)
            assertTrue(doneStatus.isMultiEmployeeMode)
            assertEquals(2, doneStatus.completedCount)
            assertTrue(doneStatus.allCompleted)
            assertTrue(doneStatus.incompleteEmployeeNames.isEmpty())
        } finally {
            photo1.delete()
            photo2.delete()
        }
    }

    @Test
    fun pendingCheckOutLock_persistsLivePhotoGpsNtpAndPdfAcrossStoreRecreation_andClearsOnlyOnUnlock() {
        val store = OfflineAttendanceStore(context)
        assertFalse(store.isCheckOutEmailLocked)
        assertNull(store.pendingCheckOutLock.value)

        store.savePendingCheckOutLock(
            PendingCheckOutLock(
                logId = "log_checkout_locked_1",
                employeeId = "emp_bilal",
                employeeCode = "NICH-4001",
                employeeName = "Bilal Ahmed",
                department = "Engineering",
                verificationMethod = "FACE_SCAN_1",
                confidenceScore = 95,
                loggedTimeLabel = "05:45:00 PM",
                loggedDateKey = "2026-11-17",
                gpsCoordinates = "24.860734, 67.001136 (±8m)",
                locationAddress = "NICH Campus, Karachi",
                timeSource = "NTP Verified (time.google.com)",
                liveSelfiePath = "/tmp/selfie_checkout.jpg",
                pdfReportPath = "",
                isConsolidatedBatch = true,
                batchDateKey = "2026-11-17",
                batchEmployeeCount = 2
            )
        )
        store.updatePendingCheckOutPdfPath("log_checkout_locked_1", "/tmp/report_checkout.pdf")

        // Simulate app process kill & cold relaunch with a fresh OfflineAttendanceStore instance
        val restartedStore = OfflineAttendanceStore(context)
        assertTrue(restartedStore.isCheckOutEmailLocked)
        val restored = restartedStore.pendingCheckOutLock.value
        assertNotNull(restored)
        assertEquals("log_checkout_locked_1", restored!!.logId)
        assertEquals("Bilal Ahmed", restored.employeeName)
        assertEquals("24.860734, 67.001136 (±8m)", restored.gpsCoordinates)
        assertEquals("NICH Campus, Karachi", restored.locationAddress)
        assertEquals("NTP Verified (time.google.com)", restored.timeSource)
        assertEquals("/tmp/selfie_checkout.jpg", restored.liveSelfiePath)
        assertEquals("/tmp/report_checkout.pdf", restored.pdfReportPath)
        assertTrue(restored.isConsolidatedBatch)
        assertEquals(2, restored.batchEmployeeCount)
        assertTrue("log_checkout_locked_1" in restartedStore.pendingEmailLogIds.value)

        // Unlock only after confirmation clear
        restartedStore.clearPendingCheckOutLock("log_checkout_locked_1")
        val unlockedStore = OfflineAttendanceStore(context)
        assertFalse(unlockedStore.isCheckOutEmailLocked)
        assertNull(unlockedStore.pendingCheckOutLock.value)
        assertFalse("log_checkout_locked_1" in unlockedStore.pendingEmailLogIds.value)
    }
}
