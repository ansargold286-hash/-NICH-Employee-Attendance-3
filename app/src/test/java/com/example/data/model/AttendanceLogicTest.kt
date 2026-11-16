package com.example.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceLogicTest {

    @Test
    fun determineEventType_morningHours_returnsCheckIn() {
        assertEquals(AttendanceEventType.CHECK_IN, AttendanceTimeRules.determineEventType(8))
        assertEquals(AttendanceEventType.CHECK_IN, AttendanceTimeRules.determineEventType(9))
        assertEquals(AttendanceEventType.CHECK_IN, AttendanceTimeRules.determineEventType(11))
    }

    @Test
    fun determineEventType_noonAndAfternoonHours_returnsCheckOut() {
        assertEquals(AttendanceEventType.CHECK_OUT, AttendanceTimeRules.determineEventType(12))
        assertEquals(AttendanceEventType.CHECK_OUT, AttendanceTimeRules.determineEventType(14))
        assertEquals(AttendanceEventType.CHECK_OUT, AttendanceTimeRules.determineEventType(18))
        assertEquals(AttendanceEventType.CHECK_OUT, AttendanceTimeRules.determineEventType(23))
    }

    @Test
    fun faceBiometricMatcher_enforcesFiftyPercentThresholdAndSeparatesMultipleEmployees() {
        val employeeA = Employee(
            id = "emp_a",
            name = "Bilal Ahmed",
            faceSignature = FaceBiometricMatcher.encodeVectorSignature(
                listOf(
                    0.4200f, 0.4800f, 0.6200f, 0.7400f,
                    0.2700f, 0.7500f, 0.7100f, 0.9600f,
                    0.7800f, 0.3400f, 0.3400f, 0.5600f,
                    2.1000f, 0.4500f, 0.2900f, 0.1400f
                )
            ),
            isActive = true
        )
        val employeeB = Employee(
            id = "emp_b",
            name = "Dr. Ananya Rao",
            faceSignature = FaceBiometricMatcher.encodeVectorSignature(
                listOf(
                    0.3400f, 0.3800f, 0.5100f, 0.8600f,
                    0.1900f, 0.5900f, 0.8800f, 0.9100f,
                    0.8900f, 0.4400f, 0.4500f, 0.7200f,
                    2.5500f, 0.6200f, 0.3900f, 0.2100f
                )
            ),
            isActive = true
        )
        val roster = listOf(employeeA, employeeB)

        val scanForA = FaceBiometricMatcher.encodeVectorSignature(
            listOf(
                0.4220f, 0.4820f, 0.6180f, 0.7420f,
                0.2710f, 0.7490f, 0.7120f, 0.9580f,
                0.7820f, 0.3420f, 0.3390f, 0.5620f,
                2.1040f, 0.4520f, 0.2910f, 0.1410f
            )
        )
        val matchA = FaceBiometricMatcher.matchBestEmployee(scanForA, roster)
        assertNotNull(matchA)
        assertEquals("emp_a", matchA?.employee?.id)
        assertEquals("Bilal Ahmed", matchA?.employee?.name)
        assertEquals(true, (matchA?.confidencePercent ?: 0) >= 50)

        // Lighting/angle-shifted scan (~61%) must still pass cleanly at >= 50%
        val partialScanForA = FaceBiometricMatcher.encodeVectorSignature(
            listOf(
                0.5800f, 0.6400f, 0.7800f, 0.9000f,
                0.4300f, 0.9100f, 0.8700f, 1.1200f,
                0.9400f, 0.5000f, 0.5000f, 0.7200f,
                1.8500f, 0.6100f, 0.4500f, 0.3000f
            )
        )
        val partialMatchA = FaceBiometricMatcher.matchBestEmployee(partialScanForA, roster)
        assertNotNull(partialMatchA)
        assertEquals("emp_a", partialMatchA?.employee?.id)
        assertEquals(true, (partialMatchA?.confidencePercent ?: 0) in 50..89)

        val scanForB = FaceBiometricMatcher.encodeVectorSignature(
            listOf(
                0.3410f, 0.3820f, 0.5080f, 0.8580f,
                0.1910f, 0.5920f, 0.8780f, 0.9120f,
                0.8880f, 0.4410f, 0.4480f, 0.7190f,
                2.5480f, 0.6180f, 0.3910f, 0.2090f
            )
        )
        val matchB = FaceBiometricMatcher.matchBestEmployee(scanForB, roster)
        assertNotNull(matchB)
        assertEquals("emp_b", matchB?.employee?.id)
        assertEquals("Dr. Ananya Rao", matchB?.employee?.name)
        assertEquals(true, (matchB?.confidencePercent ?: 0) >= 50)

        // Unrecognized stranger (< 50%) must still be rejected cleanly (no single-name fallback)
        val strangerScan = FaceBiometricMatcher.encodeVectorSignature(
            listOf(
                0.6100f, 0.7900f, 0.9500f, 0.5200f,
                0.5500f, 1.1500f, 0.4800f, 0.7200f,
                0.5600f, 0.1900f, 0.2000f, 0.9500f,
                1.4500f, 0.2600f, 0.5800f, 0.3400f
            )
        )
        assertNull(FaceBiometricMatcher.matchBestEmployee(strangerScan, roster))
        assertNull(FaceBiometricMatcher.matchBestEmployee(scanForA, emptyList()))
    }

    @Test
    fun passcodeResolver_resolvesDedicatedCheckInAndCheckOutCodes() {
        val employees = listOf(
            Employee(
                id = "emp_muhammad_ansar",
                employeeCode = "NICH-0147",
                name = "محمد انصار (Muhammad Ansar)",
                passcode = "147",
                checkInCode = "147",
                checkOutCode = "369",
                isActive = true
            ),
            Employee(
                id = "emp_nich_1042",
                employeeCode = "NICH-1042",
                name = "Dr. Ananya Rao",
                passcode = "1042",
                isActive = true
            )
        )

        val checkIn = PasscodeResolver.resolve("147", employees, AttendanceEventType.CHECK_OUT)
        assertNotNull(checkIn)
        assertEquals("emp_muhammad_ansar", checkIn?.employee?.id)
        assertEquals(AttendanceEventType.CHECK_IN, checkIn?.eventType)

        val checkOut = PasscodeResolver.resolve("369", employees, AttendanceEventType.CHECK_IN)
        assertNotNull(checkOut)
        assertEquals("emp_muhammad_ansar", checkOut?.employee?.id)
        assertEquals(AttendanceEventType.CHECK_OUT, checkOut?.eventType)

        val fallbackTime = PasscodeResolver.resolve("1042", employees, AttendanceEventType.CHECK_OUT)
        assertNotNull(fallbackTime)
        assertEquals(AttendanceEventType.CHECK_OUT, fallbackTime?.eventType)

        assertNull(PasscodeResolver.resolve("9999", employees, AttendanceEventType.CHECK_IN))
    }

    @Test
    fun livenessEvaluator_validatesDirectionalHeadPosesAndSequence() {
        val sequence = LivenessDirection.buildChallengeSequence(seed = 42L)
        assertEquals(3, sequence.size)
        assertEquals(LivenessDirection.CENTER_VERIFY, sequence.last())
        assertEquals(true, sequence[0] != sequence[1])

        assertEquals(
            true,
            LivenessEvaluator.isDirectionSatisfied(
                direction = LivenessDirection.TURN_LEFT,
                headYawDeg = 14f,
                headPitchDeg = 0f,
                isFrontCamera = true
            )
        )
        assertEquals(
            false,
            LivenessEvaluator.isDirectionSatisfied(
                direction = LivenessDirection.TURN_LEFT,
                headYawDeg = -14f,
                headPitchDeg = 0f,
                isFrontCamera = true
            )
        )
        assertEquals(
            true,
            LivenessEvaluator.isDirectionSatisfied(
                direction = LivenessDirection.TURN_RIGHT,
                headYawDeg = -13f,
                headPitchDeg = 0f,
                isFrontCamera = true
            )
        )
        assertEquals(
            true,
            LivenessEvaluator.isDirectionSatisfied(
                direction = LivenessDirection.LOOK_UP,
                headYawDeg = 0f,
                headPitchDeg = 12f,
                isFrontCamera = true
            )
        )
        assertEquals(
            true,
            LivenessEvaluator.isDirectionSatisfied(
                direction = LivenessDirection.LOOK_DOWN,
                headYawDeg = 0f,
                headPitchDeg = -11f,
                isFrontCamera = true
            )
        )
        assertEquals(
            true,
            LivenessEvaluator.isDirectionSatisfied(
                direction = LivenessDirection.CENTER_VERIFY,
                headYawDeg = 3f,
                headPitchDeg = -2f,
                isFrontCamera = true
            )
        )
    }

    @Test
    fun bidiTextHelper_isolatesMixedEnglishUrduWithoutReversingParentheses() {
        val formatted = BidiTextHelper.formatEmployeeHeaderTitle(
            rawName = "محمد انصار (Muhammad Ansar)",
            employeeCode = "NICH-0147"
        )
        assertEquals(true, formatted.contains("\u2067محمد انصار\u2069"))
        assertEquals("Muhammad Ansar / محمد انصار - NICH-0147", BidiTextHelper.stripBidiControls(formatted))
    }
}
