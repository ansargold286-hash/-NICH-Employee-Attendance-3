package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

@IgnoreExtraProperties
data class Employee(
    @DocumentId val id: String = "",
    val userId: String = "",
    val employeeCode: String = "",
    val name: String = "",
    val department: String = "",
    val roleTitle: String = "",
    val passcode: String = "",
    val checkInCode: String = "",
    val checkOutCode: String = "",
    val avatarKey: String = "avatar_1",
    val faceSignature: String = "",
    val isActive: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class PasscodeMatchResult(
    val employee: Employee,
    val eventType: AttendanceEventType
)

@IgnoreExtraProperties
data class AttendanceLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val employeeId: String = "",
    val employeeCode: String = "",
    val employeeName: String = "",
    val department: String = "",
    val avatarKey: String = "avatar_1",
    val eventType: String = AttendanceEventType.CHECK_IN.wireValue,
    val verificationMethod: String = VerificationMethod.FACE_SCAN_1.wireValue,
    val confidenceScore: Long = 0L,
    val loggedTimeLabel: String = "",
    val loggedDateKey: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

enum class AttendanceEventType(val wireValue: String, val displayLabel: String) {
    CHECK_IN("CHECK_IN", "CHECK-IN"),
    CHECK_OUT("CHECK_OUT", "CHECK-OUT");

    companion object {
        fun fromWire(value: String): AttendanceEventType =
            entries.firstOrNull { it.wireValue == value } ?: CHECK_IN
    }
}

enum class VerificationMethod(val wireValue: String, val displayLabel: String) {
    FACE_SCAN_1("FACE_SCAN_1", "Biometric Face Scan (Pass 1)"),
    FACE_SCAN_2("FACE_SCAN_2", "Biometric Face Scan (Auto-Retry)"),
    PASSCODE("PASSCODE", "Employee Passcode Fallback");

    companion object {
        fun fromWire(value: String): VerificationMethod =
            entries.firstOrNull { it.wireValue == value } ?: PASSCODE
    }
}

object PasscodeResolver {
    fun resolve(
        rawCode: String,
        employees: List<Employee>,
        fallbackTimeEventType: AttendanceEventType
    ): PasscodeMatchResult? {
        val code = rawCode.trim()
        if (code.isEmpty()) return null
        val active = employees.filter { it.isActive }
        active.firstOrNull { it.checkInCode.isNotBlank() && it.checkInCode == code }?.let {
            return PasscodeMatchResult(employee = it, eventType = AttendanceEventType.CHECK_IN)
        }
        active.firstOrNull { it.checkOutCode.isNotBlank() && it.checkOutCode == code }?.let {
            return PasscodeMatchResult(employee = it, eventType = AttendanceEventType.CHECK_OUT)
        }
        active.firstOrNull { it.passcode == code }?.let {
            return PasscodeMatchResult(employee = it, eventType = fallbackTimeEventType)
        }
        return null
    }
}

object AttendanceTimeRules {
    /**
     * Morning (8:00 AM to 12:00 PM, and pre-noon hours < 12): CHECK_IN.
     * Afternoon / Post-12:00 PM (12:00 PM onwards, hour >= 12): CHECK_OUT.
     */
    fun determineEventType(hourOfDay24: Int): AttendanceEventType {
        return if (hourOfDay24 < 12) {
            AttendanceEventType.CHECK_IN
        } else {
            AttendanceEventType.CHECK_OUT
        }
    }

    fun currentEventType(calendar: Calendar = Calendar.getInstance()): AttendanceEventType {
        return determineEventType(calendar.get(Calendar.HOUR_OF_DAY))
    }

    fun windowDescription(hourOfDay24: Int): String {
        return when {
            hourOfDay24 in 8..11 -> "Morning Window (08:00–12:00) • Auto Check-In"
            hourOfDay24 < 8 -> "Early Morning (<08:00) • Auto Check-In"
            else -> "Afternoon Window (12:00+) • Auto Check-Out"
        }
    }

    fun formatTimeLabel(calendar: Calendar = Calendar.getInstance()): String {
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val amPm = if (hour24 < 12) "AM" else "PM"
        val hour12 = when (val h = hour24 % 12) {
            0 -> 12
            else -> h
        }
        return String.format(Locale.US, "%02d:%02d:%02d %s", hour12, minute, second, amPm)
    }

    fun formatDateKey(calendar: Calendar = Calendar.getInstance()): String {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }
}

data class FaceMatchResult(
    val employee: Employee,
    val confidencePercent: Int
)

data class FaceEvaluationOutcome(
    val match: FaceMatchResult?,
    val bestConfidencePercent: Int,
    val closestEmployeeName: String? = null
)

enum class LivenessDirection(
    val instruction: String,
    val shortLabel: String
) {
    TURN_LEFT(
        instruction = "TURN FACE SLIGHTLY LEFT",
        shortLabel = "LOOK LEFT"
    ),
    TURN_RIGHT(
        instruction = "TURN FACE SLIGHTLY RIGHT",
        shortLabel = "LOOK RIGHT"
    ),
    LOOK_UP(
        instruction = "TILT FACE SLIGHTLY UP",
        shortLabel = "LOOK UP"
    ),
    LOOK_DOWN(
        instruction = "TILT FACE SLIGHTLY DOWN",
        shortLabel = "LOOK DOWN"
    ),
    CENTER_VERIFY(
        instruction = "LOOK STRAIGHT • VERIFYING & CAPTURING SELFIE",
        shortLabel = "CENTER MATCH"
    );

    companion object {
        fun buildChallengeSequence(seed: Long = System.currentTimeMillis()): List<LivenessDirection> {
            val directionalPool = listOf(TURN_LEFT, TURN_RIGHT, LOOK_UP, LOOK_DOWN)
            val firstIdx = ((seed and 0x7FFFFFFFL) % directionalPool.size).toInt()
            val secondIdx = (firstIdx + 1 + ((seed ushr 3 and 0x7FFFFFFFL) % 3).toInt()) % directionalPool.size
            return listOf(directionalPool[firstIdx], directionalPool[secondIdx], CENTER_VERIFY)
        }
    }
}

object LivenessEvaluator {
    private const val YAW_THRESHOLD_DEG = 10.5f
    private const val PITCH_THRESHOLD_DEG = 8.5f

    fun isDirectionSatisfied(
        direction: LivenessDirection,
        headYawDeg: Float,
        headPitchDeg: Float,
        isFrontCamera: Boolean = true
    ): Boolean {
        val mirroredYaw = if (isFrontCamera) headYawDeg else -headYawDeg
        return when (direction) {
            LivenessDirection.TURN_LEFT -> mirroredYaw >= YAW_THRESHOLD_DEG
            LivenessDirection.TURN_RIGHT -> mirroredYaw <= -YAW_THRESHOLD_DEG
            LivenessDirection.LOOK_UP -> headPitchDeg >= PITCH_THRESHOLD_DEG
            LivenessDirection.LOOK_DOWN -> headPitchDeg <= -PITCH_THRESHOLD_DEG
            LivenessDirection.CENTER_VERIFY ->
                abs(headYawDeg) <= 18f && abs(headPitchDeg) <= 18f
        }
    }
}

data class FaceAnalysisFrame(
    val faceDetected: Boolean,
    val eyesLocked: Boolean = false,
    val noseMustacheLocked: Boolean = false,
    val jawBeardLocked: Boolean = false,
    val signature: String? = null,
    val livenessOpenEyes: Boolean = true,
    val headPoseAligned: Boolean = true,
    val headYawDeg: Float = 0f,
    val headPitchDeg: Float = 0f,
    val headRollDeg: Float = 0f,
    val isFrontCamera: Boolean = true,
    val selfieImagePath: String? = null,
    val qualityIssue: String? = null
)

object FaceBiometricMatcher {
    private const val MATCH_THRESHOLD_PERCENT = 82
    const val FULL_FEATURE_DIMENSION = 8
    const val LEGACY_FEATURE_DIMENSION = 4

    fun encodeSignature(
        eyeToBoxRatio: Float,
        noseToEyeRatio: Float,
        mouthToEyeRatio: Float,
        boxAspectRatio: Float
    ): String {
        return String.format(
            Locale.US,
            "%.4f:%.4f:%.4f:%.4f",
            eyeToBoxRatio,
            noseToEyeRatio,
            mouthToEyeRatio,
            boxAspectRatio
        )
    }

    fun encodeFullSignature(
        eyeToBoxRatio: Float,
        noseToEyeRatio: Float,
        mouthToEyeRatio: Float,
        boxAspectRatio: Float,
        philtrumToEyeRatio: Float,
        jawChinDropRatio: Float,
        jawToCheekWidthRatio: Float,
        symmetryBalanceRatio: Float
    ): String {
        return String.format(
            Locale.US,
            "%.4f:%.4f:%.4f:%.4f:%.4f:%.4f:%.4f:%.4f",
            eyeToBoxRatio,
            noseToEyeRatio,
            mouthToEyeRatio,
            boxAspectRatio,
            philtrumToEyeRatio,
            jawChinDropRatio,
            jawToCheekWidthRatio,
            symmetryBalanceRatio
        )
    }

    fun parseSignature(raw: String): List<Float>? {
        if (raw.isBlank()) return null
        val parts = raw.split(":").mapNotNull { it.toFloatOrNull() }
        return if (parts.size == FULL_FEATURE_DIMENSION || parts.size == LEGACY_FEATURE_DIMENSION) {
            parts
        } else {
            null
        }
    }

    fun evaluateFrame(
        liveSignature: String,
        employees: List<Employee>
    ): FaceEvaluationOutcome {
        val liveVec = parseSignature(liveSignature)
            ?: return FaceEvaluationOutcome(match = null, bestConfidencePercent = 0)
        var bestEmployee: Employee? = null
        var bestScore = 0

        for (employee in employees) {
            if (!employee.isActive) continue
            val enrolledVec = parseSignature(employee.faceSignature) ?: continue
            val comparedDims = minOf(liveVec.size, enrolledVec.size)
            if (comparedDims < LEGACY_FEATURE_DIMENSION) continue

            var sumSq = 0.0
            for (i in 0 until comparedDims) {
                val weight = if (i < 4) 1.0 else 1.15
                val diff = (liveVec[i] - enrolledVec[i]).toDouble() * weight
                sumSq += diff * diff
            }
            val rmsDistance = sqrt(sumSq / comparedDims.toDouble())
            // RMS similarity scaling: ~0.00 -> 99%, ~0.07 -> ~82%, >=0.12 -> <=70% (rejected)
            val score = (99.0 - (rmsDistance * 240.0)).toInt().coerceIn(0, 99)
            if (score > bestScore) {
                bestScore = score
                bestEmployee = employee
            }
        }

        val candidate = bestEmployee
            ?: return FaceEvaluationOutcome(match = null, bestConfidencePercent = 0)
        val matched = if (bestScore >= MATCH_THRESHOLD_PERCENT) {
            FaceMatchResult(employee = candidate, confidencePercent = bestScore)
        } else {
            null
        }
        return FaceEvaluationOutcome(
            match = matched,
            bestConfidencePercent = bestScore,
            closestEmployeeName = candidate.name
        )
    }

    fun matchBestEmployee(
        liveSignature: String,
        employees: List<Employee>
    ): FaceMatchResult? = evaluateFrame(liveSignature, employees).match
}
