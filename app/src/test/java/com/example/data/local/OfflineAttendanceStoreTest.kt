package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AttendanceEventType
import com.example.data.model.Employee
import com.example.data.model.VerificationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        tempPhoto1.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        tempPhoto2.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))

        try {
            val saved = store.registerEmployeesBatch(
                listOf(
                    LocalRegistrationInput(
                        name = "Ayesha Khan",
                        department = "Diagnostics",
                        photoPath = tempPhoto1.absolutePath,
                        faceSignature = "0.4200:0.4800:0.6200:0.7400:0.2700:0.7500:0.7100:0.9600"
                    ),
                    LocalRegistrationInput(
                        name = "Zainab Tariq",
                        department = "Radiology",
                        photoPath = tempPhoto2.absolutePath,
                        faceSignature = "0.3400:0.3800:0.5100:0.8600:0.1900:0.5900:0.8800:0.9100"
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
            assertTrue(zainab!!.localPhotoPath.isNotBlank())
            assertTrue(java.io.File(zainab.localPhotoPath).exists())
        } finally {
            tempPhoto1.delete()
            tempPhoto2.delete()
        }
    }
}
