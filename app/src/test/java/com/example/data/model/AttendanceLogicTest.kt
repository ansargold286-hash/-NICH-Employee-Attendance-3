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
    fun faceBiometricMatcher_matchesCloseAndPartialVectorsAtOnePercentThreshold() {
        val enrolled = listOf(
            Employee(
                id = "emp_1",
                name = "Dr. Ananya Rao",
                faceSignature = "0.3600:0.4200:0.6500:0.7800",
                isActive = true
            )
        )

        val closeMatch = FaceBiometricMatcher.matchBestEmployee("0.3650:0.4220:0.6480:0.7820", enrolled)
        assertNotNull(closeMatch)
        assertEquals("emp_1", closeMatch?.employee?.id)

        val partialMatch = FaceBiometricMatcher.matchBestEmployee("0.5900:0.7500:0.3500:0.9500", enrolled)
        assertNotNull(partialMatch)
        assertEquals("emp_1", partialMatch?.employee?.id)
        assertEquals(true, (partialMatch?.confidencePercent ?: 0) >= 1)
        assertEquals(true, (closeMatch?.confidencePercent ?: 0) > (partialMatch?.confidencePercent ?: 0))

        assertNull(FaceBiometricMatcher.matchBestEmployee("0.3650:0.4220:0.6480:0.7820", emptyList()))
    }

    @Test
    fun faceBiometricMatcher_analyzesFullEightFeatureBeardMustacheEyeVectorsWithOnePercentFloor() {
        val enrolledFull = listOf(
            Employee(
                id = "emp_muhammad_ansar",
                name = "محمد انصار (Muhammad Ansar)",
                faceSignature = FaceBiometricMatcher.encodeFullSignature(
                    eyeToBoxRatio = 0.435f,
                    noseToEyeRatio = 0.480f,
                    mouthToEyeRatio = 0.640f,
                    boxAspectRatio = 0.750f,
                    philtrumToEyeRatio = 0.280f,
                    jawChinDropRatio = 0.760f,
                    jawToCheekWidthRatio = 0.730f,
                    symmetryBalanceRatio = 0.950f
                ),
                isActive = true
            )
        )

        val alignedFull = FaceBiometricMatcher.encodeFullSignature(
            eyeToBoxRatio = 0.440f,
            noseToEyeRatio = 0.485f,
            mouthToEyeRatio = 0.635f,
            boxAspectRatio = 0.755f,
            philtrumToEyeRatio = 0.285f,
            jawChinDropRatio = 0.750f,
            jawToCheekWidthRatio = 0.740f,
            symmetryBalanceRatio = 0.945f
        )
        val matched = FaceBiometricMatcher.matchBestEmployee(alignedFull, enrolledFull)
        assertNotNull(matched)
        assertEquals("emp_muhammad_ansar", matched?.employee?.id)

        val partialOrDistantStructure = FaceBiometricMatcher.encodeFullSignature(
            eyeToBoxRatio = 0.310f,
            noseToEyeRatio = 0.620f,
            mouthToEyeRatio = 0.450f,
            boxAspectRatio = 0.910f,
            philtrumToEyeRatio = 0.410f,
            jawChinDropRatio = 0.510f,
            jawToCheekWidthRatio = 0.920f,
            symmetryBalanceRatio = 0.760f
        )
        val flexibleMatch = FaceBiometricMatcher.matchBestEmployee(partialOrDistantStructure, enrolledFull)
        assertNotNull(flexibleMatch)
        assertEquals("emp_muhammad_ansar", flexibleMatch?.employee?.id)
        assertEquals(true, (flexibleMatch?.confidencePercent ?: 0) >= 1)
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
