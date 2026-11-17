package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    @ServerTimestamp val updatedAt: Timestamp? = null,
    @get:Exclude val localPhotoPath: String = ""
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
    val gpsCoordinates: String = "",
    val locationAddress: String = "",
    val timeSource: String = "",
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
    FACE_SCAN_1("FACE_SCAN_1", "Biometric Face Scan (50%+ Verified)"),
    FACE_SCAN_2("FACE_SCAN_2", "Biometric Face Scan (Pass 2)"),
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
     * Morning (before 12:00 PM): CHECK_IN.
     * Afternoon (12:00 PM onwards): CHECK_OUT.
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
        instruction = "LOOK STRAIGHT • VERIFYING FACE",
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
    private const val YAW_THRESHOLD_DEG = 8.0f
    private const val PITCH_THRESHOLD_DEG = 6.5f

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
                abs(headYawDeg) <= 25f && abs(headPitchDeg) <= 25f
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
    val leftEyeOpenProbability: Float? = null,
    val rightEyeOpenProbability: Float? = null,
    val smilingProbability: Float? = null,
    val faceCenterXNorm: Float? = null,
    val faceCenterYNorm: Float? = null,
    val isFrontCamera: Boolean = true,
    val selfieImagePath: String? = null,
    val qualityIssue: String? = null
)

data class AntiSpoofingCheckResult(
    val livenessVerified: Boolean,
    val spoofSuspected: Boolean,
    val statusPrompt: String
)

/**
 * Multi-frame anti-spoofing liveness tracker that defeats printed photos and mobile screenshots
 * by requiring live biological signals from ML Kit:
 * 1) Natural eye blink (open -> blink dip -> reopen), OR
 * 2) Dynamic smile / expression transition (smile with expression shift or micro-motion), OR
 * 3) Live 3D head pose motion accompanied by subtle eye/smile classification variation.
 */
class AntiSpoofingLivenessTracker {
    private var frameCount = 0
    private var sawOpenEyesBeforeBlink = false
    private var sawBlinkDip = false
    private var blinkCompleted = false
    private var dynamicSmileCompleted = false
    private var livenessLockedVerified = false

    private var minEyeProb = 1f
    private var maxEyeProb = 0f
    private var minSmileProb = 1f
    private var maxSmileProb = 0f

    private var minYaw = Float.MAX_VALUE
    private var maxYaw = -Float.MAX_VALUE
    private var minPitch = Float.MAX_VALUE
    private var maxPitch = -Float.MAX_VALUE
    private var minCenterX = Float.MAX_VALUE
    private var maxCenterX = -Float.MAX_VALUE

    fun reset() {
        frameCount = 0
        sawOpenEyesBeforeBlink = false
        sawBlinkDip = false
        blinkCompleted = false
        dynamicSmileCompleted = false
        livenessLockedVerified = false
        minEyeProb = 1f
        maxEyeProb = 0f
        minSmileProb = 1f
        maxSmileProb = 0f
        minYaw = Float.MAX_VALUE
        maxYaw = -Float.MAX_VALUE
        minPitch = Float.MAX_VALUE
        maxPitch = -Float.MAX_VALUE
        minCenterX = Float.MAX_VALUE
        maxCenterX = -Float.MAX_VALUE
    }

    fun evaluate(frame: FaceAnalysisFrame): AntiSpoofingCheckResult {
        if (!frame.faceDetected) {
            if (frameCount > 0) reset()
            return AntiSpoofingCheckResult(
                livenessVerified = false,
                spoofSuspected = false,
                statusPrompt = "Position your live face inside the scanner frame"
            )
        }

        frameCount++

        if (frame.headYawDeg < minYaw) minYaw = frame.headYawDeg
        if (frame.headYawDeg > maxYaw) maxYaw = frame.headYawDeg
        if (frame.headPitchDeg < minPitch) minPitch = frame.headPitchDeg
        if (frame.headPitchDeg > maxPitch) maxPitch = frame.headPitchDeg

        val cx = frame.faceCenterXNorm
        if (cx != null) {
            if (cx < minCenterX) minCenterX = cx
            if (cx > maxCenterX) maxCenterX = cx
        }

        val eyeProbs = listOfNotNull(frame.leftEyeOpenProbability, frame.rightEyeOpenProbability)
        val avgEyeOpen = if (eyeProbs.isNotEmpty()) {
            eyeProbs.average().toFloat()
        } else if (!frame.livenessOpenEyes) {
            0.15f
        } else {
            null
        }

        if (avgEyeOpen != null) {
            if (avgEyeOpen < minEyeProb) minEyeProb = avgEyeOpen
            if (avgEyeOpen > maxEyeProb) maxEyeProb = avgEyeOpen

            if (!sawOpenEyesBeforeBlink && avgEyeOpen >= EYE_OPEN_THRESHOLD) {
                sawOpenEyesBeforeBlink = true
            } else if (sawOpenEyesBeforeBlink && !sawBlinkDip && avgEyeOpen <= EYE_BLINK_THRESHOLD) {
                sawBlinkDip = true
            } else if (sawBlinkDip && avgEyeOpen >= EYE_OPEN_THRESHOLD) {
                blinkCompleted = true
            }
        }

        val smile = frame.smilingProbability
        if (smile != null) {
            if (smile < minSmileProb) minSmileProb = smile
            if (smile > maxSmileProb) maxSmileProb = smile
        }

        val yawSpan = (maxYaw - minYaw).coerceAtLeast(0f)
        val pitchSpan = (maxPitch - minPitch).coerceAtLeast(0f)
        val poseSpan = yawSpan + pitchSpan
        val eyeSpan = (maxEyeProb - minEyeProb).coerceAtLeast(0f)
        val smileSpan = (maxSmileProb - minSmileProb).coerceAtLeast(0f)
        val centerShift = if (minCenterX <= maxCenterX) (maxCenterX - minCenterX) else 0f

        if (smile != null && smile >= SMILE_ACTIVE_THRESHOLD &&
            (smileSpan >= SMILE_DELTA_THRESHOLD || poseSpan >= 1.5f || centerShift >= 0.015f)
        ) {
            dynamicSmileCompleted = true
        }

        val natural3DMotionVerified = frameCount >= 2 &&
            poseSpan >= MOTION_POSE_SPAN_THRESHOLD &&
            (eyeSpan >= 0.04f || smileSpan >= 0.06f)

        if (blinkCompleted || dynamicSmileCompleted || natural3DMotionVerified) {
            livenessLockedVerified = true
        }

        if (livenessLockedVerified) {
            return AntiSpoofingCheckResult(
                livenessVerified = true,
                spoofSuspected = false,
                statusPrompt = "Liveness Verified • Face Scan Active"
            )
        }

        val staticPhotoSuspected = frameCount >= STATIC_SPOOF_FRAME_LIMIT &&
            poseSpan < 1.2f &&
            centerShift < 0.01f &&
            eyeSpan < 0.05f &&
            smileSpan < 0.05f &&
            !sawBlinkDip

        val prompt = when {
            staticPhotoSuspected ->
                "Spoof Alert: Static photo or screen detected — Blink eyes or smile naturally"
            sawBlinkDip ->
                "Blink detected — Open eyes and look straight"
            else ->
                "Liveness Check: Blink your eyes or smile naturally"
        }

        return AntiSpoofingCheckResult(
            livenessVerified = false,
            spoofSuspected = staticPhotoSuspected,
            statusPrompt = prompt
        )
    }

    companion object {
        private const val EYE_OPEN_THRESHOLD = 0.50f
        private const val EYE_BLINK_THRESHOLD = 0.42f
        private const val SMILE_ACTIVE_THRESHOLD = 0.55f
        private const val SMILE_DELTA_THRESHOLD = 0.16f
        private const val MOTION_POSE_SPAN_THRESHOLD = 7.0f
        private const val STATIC_SPOOF_FRAME_LIMIT = 8
    }
}

object FaceBiometricMatcher {
    const val MATCH_THRESHOLD_PERCENT = 50
    const val HIGH_PRECISION_DIMENSION = 16
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

    fun encodeVectorSignature(features: List<Float>): String {
        return features.joinToString(":") { value ->
            String.format(Locale.US, "%.4f", value)
        }
    }

    fun parseSignature(raw: String): List<Float>? {
        if (raw.isBlank()) return null
        val parts = raw.split(":").mapNotNull { it.toFloatOrNull() }
        return if (parts.size in LEGACY_FEATURE_DIMENSION..32) {
            parts
        } else {
            null
        }
    }

    /**
     * Compares the live facial feature vector against ALL active local-storage
     * employee profiles that have enrolled biometric embeddings.
     * - Eliminates false fallback to any default employee when confidence < 50%.
     * - Scores using weighted RMS distance + L-infinity peak feature difference so two
     *   distinct faces separate cleanly while same-person scans pass reliably at 50%+
     *   even under lighting or angle variation.
     */
    fun evaluateFrame(
        liveSignature: String,
        employees: List<Employee>
    ): FaceEvaluationOutcome {
        val enrolledEmployees = employees.filter {
            it.isActive && parseSignature(it.faceSignature) != null
        }
        if (enrolledEmployees.isEmpty()) {
            return FaceEvaluationOutcome(match = null, bestConfidencePercent = 0)
        }

        val liveVec = parseSignature(liveSignature)
            ?: return FaceEvaluationOutcome(match = null, bestConfidencePercent = 0)

        var bestEmployee: Employee? = null
        var bestScore = 0
        var closestName: String? = null

        for (employee in enrolledEmployees) {
            val enrolledVec = parseSignature(employee.faceSignature) ?: continue
            val comparedDims = min(liveVec.size, enrolledVec.size)
            if (comparedDims < LEGACY_FEATURE_DIMENSION) continue

            val score = computeSimilarityScorePercent(
                liveVec = liveVec,
                enrolledVec = enrolledVec,
                comparedDims = comparedDims
            )
            if (score > bestScore) {
                bestScore = score
                bestEmployee = employee
                closestName = employee.name
            }
        }

        val matched = if (bestEmployee != null && bestScore >= MATCH_THRESHOLD_PERCENT) {
            FaceMatchResult(employee = bestEmployee, confidencePercent = bestScore)
        } else {
            null
        }

        return FaceEvaluationOutcome(
            match = matched,
            bestConfidencePercent = bestScore,
            closestEmployeeName = closestName
        )
    }

    internal fun computeSimilarityScorePercent(
        liveVec: List<Float>,
        enrolledVec: List<Float>,
        comparedDims: Int
    ): Int {
        var sumSq = 0.0
        var maxAbsDiff = 0.0
        for (i in 0 until comparedDims) {
            val diff = abs(liveVec[i].toDouble() - enrolledVec[i].toDouble())
            sumSq += diff * diff
            maxAbsDiff = max(maxAbsDiff, diff)
        }
        val rmsDistance = sqrt(sumSq / comparedDims.toDouble())
        // Penalize both overall Euclidean drift and any single-feature mismatch
        val penalty = (rmsDistance * 165.0) + (maxAbsDiff * 42.0)
        return (100.0 - penalty).toInt().coerceIn(0, 100)
    }

    fun matchBestEmployee(
        liveSignature: String,
        employees: List<Employee>
    ): FaceMatchResult? = evaluateFrame(liveSignature, employees).match
}

object BidiTextHelper {
    private const val LRI = "\u2066"
    private const val RLI = "\u2067"
    private const val PDI = "\u2069"
    private const val LRM = "\u200E"

    private val arabicUrduRegex = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]+(?:\\s+[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]+)*")
    private val parensLatinRegex = Regex("\\(([A-Za-z0-9][A-Za-z0-9 .,'_-]*)\\)")
    private val parensUrduRegex = Regex("\\(([\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF\\s]+)\\)")

    fun isolateMixedText(raw: String): String {
        if (raw.isBlank()) return ""
        val isolated = arabicUrduRegex.replace(raw) { match ->
            "$RLI${match.value}$PDI"
        }
        return "$LRI$isolated$PDI"
    }

    fun formatEmployeeHeaderTitle(rawName: String, employeeCode: String): String {
        val cleanName = rawName.trim()
        val cleanCode = employeeCode.trim()
        val containsArabic = arabicUrduRegex.containsMatchIn(cleanName)
        val displayTitle = if (containsArabic) {
            val latinInParens = parensLatinRegex.find(cleanName)?.groupValues?.getOrNull(1)?.trim()
            val urduWithoutLatinParens = parensLatinRegex.replace(cleanName, "").trim()
            val urduInParens = parensUrduRegex.find(cleanName)?.groupValues?.getOrNull(1)?.trim()
            val latinWithoutUrduParens = parensUrduRegex.replace(cleanName, "").trim()

            when {
                !latinInParens.isNullOrBlank() && urduWithoutLatinParens.isNotBlank() ->
                    "$LRI$latinInParens / $RLI$urduWithoutLatinParens$PDI${if (cleanCode.isNotBlank()) " - $cleanCode" else ""}$PDI"
                !urduInParens.isNullOrBlank() && latinWithoutUrduParens.isNotBlank() ->
                    "$LRI$latinWithoutUrduParens / $RLI$urduInParens$PDI${if (cleanCode.isNotBlank()) " - $cleanCode" else ""}$PDI"
                else ->
                    "$LRI${isolateMixedText(cleanName)}${if (cleanCode.isNotBlank()) "$LRM - $cleanCode" else ""}$PDI"
            }
        } else {
            val combined = if (cleanCode.isNotBlank()) "$cleanName - $cleanCode" else cleanName
            "$LRI$combined$PDI"
        }
        return displayTitle
    }

    fun isolateLtrToken(token: String): String {
        return if (token.isBlank()) "" else "$LRI${token.trim()}$PDI"
    }

    fun stripBidiControls(input: String): String {
        return buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                    '\u2066', '\u2067', '\u2068', '\u2069' -> Unit
                    else -> append(ch)
                }
            }
        }
    }
}
