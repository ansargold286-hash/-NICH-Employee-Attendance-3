package com.example.data.model

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class CnicOcrParseResult(
    val isCnicCardDetected: Boolean,
    val cnicNumber: String,
    val extractedName: String,
    val confidenceCues: Int,
    val rawText: String
)

data class CnicScanFrame(
    val cardDetected: Boolean,
    val insideGreenFrame: Boolean,
    val rawOcrText: String = "",
    val extractedName: String = "",
    val cnicNumber: String = "",
    val cnicImagePath: String? = null,
    val isRearCamera: Boolean = true,
    val qualityIssue: String? = null
)

data class CnicMatchOutcome(
    val matched: Boolean,
    val matchedName: String,
    val matchedCnicNumber: String,
    val similarityPercent: Int,
    val reason: String
)

/**
 * Pure Kotlin Pakistani CNIC front-side OCR parser & identity verification engine.
 * Works completely offline on ML Kit OCR text blocks so both registration auto-snap
 * and daily Step-B verification run deterministically and are 100% JVM unit-testable.
 */
object CnicOcrParser {

    private val cnicHyphenRegex = Regex("""\b(\d{5})[-\s]?(\d{7})[-\s]?(\d)\b""")
    private val parenEnglishRegex = Regex("""\(([^)]+)\)""")

    private val frontCues = listOf(
        "PAKISTAN",
        "NATIONAL IDENTITY CARD",
        "ISLAMIC REPUBLIC",
        "IDENTITY NUMBER",
        "FATHER NAME",
        "HUSBAND NAME",
        "COUNTRY OF STAY",
        "DATE OF BIRTH",
        "DATE OF ISSUE",
        "DATE OF EXPIRY",
        "GENDER",
        "HOLDER"
    )

    private val excludedCandidateTokens = setOf(
        "PAKISTAN", "ISLAMIC", "REPUBLIC", "OF", "NATIONAL", "IDENTITY", "CARD",
        "NAME", "FATHER", "HUSBAND", "GENDER", "MALE", "FEMALE", "COUNTRY",
        "STAY", "NUMBER", "DATE", "BIRTH", "ISSUE", "EXPIRY", "SIGNATURE",
        "HOLDER", "THE", "NADRA", "GOVT", "GOVERNMENT"
    )

    fun parseFrontOcrText(
        rawText: String,
        insideGreenFrame: Boolean = true
    ): CnicOcrParseResult {
        val cleanRaw = rawText.trim()
        if (cleanRaw.isEmpty()) {
            return CnicOcrParseResult(
                isCnicCardDetected = false,
                cnicNumber = "",
                extractedName = "",
                confidenceCues = 0,
                rawText = ""
            )
        }

        val upper = cleanRaw.uppercase(Locale.US)
        val cnicMatch = cnicHyphenRegex.find(cleanRaw)
        val normalizedCnic = if (cnicMatch != null) {
            "${cnicMatch.groupValues[1]}-${cnicMatch.groupValues[2]}-${cnicMatch.groupValues[3]}"
        } else {
            ""
        }

        val matchedCueCount = frontCues.count { cue -> upper.contains(cue) }
        val isCnicDetected = insideGreenFrame && (
            normalizedCnic.isNotBlank() ||
                matchedCueCount >= 2 ||
                (upper.contains("PAKISTAN") && upper.contains("IDENTITY"))
            )

        val extractedName = extractHolderName(cleanRaw)

        return CnicOcrParseResult(
            isCnicCardDetected = isCnicDetected,
            cnicNumber = normalizedCnic,
            extractedName = extractedName,
            confidenceCues = matchedCueCount + if (normalizedCnic.isNotBlank()) 2 else 0,
            rawText = cleanRaw
        )
    }

    internal fun extractHolderName(rawText: String): String {
        val lines = rawText.lines()
            .map { it.replace(Regex("""[^\p{L}\p{N}\s.:'-]"""), " ").trim() }
            .filter { it.isNotBlank() }

        // Pass 1: Look explicitly after "Name" label (not Father/Husband Name)
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.US)
            val isFatherOrHusband = upper.contains("FATHER") || upper.contains("HUSBAND")
            if (!isFatherOrHusband && Regex("""^NAME\b[:\s-]*.*""").matches(upper)) {
                val sameLineValue = line.replaceFirst(Regex("""(?i)^name\s*[:\-]?\s*"""), "").trim()
                val cleanedSame = sanitizeNameCandidate(sameLineValue)
                if (cleanedSame.length >= 3 && !isBoilerplateLine(cleanedSame)) {
                    return cleanedSame
                }
                val nextLine = lines.getOrNull(i + 1)
                if (!nextLine.isNullOrBlank()) {
                    val cleanedNext = sanitizeNameCandidate(nextLine)
                    if (cleanedNext.length >= 3 && !isBoilerplateLine(cleanedNext)) {
                        return cleanedNext
                    }
                }
            }
        }

        // Pass 2: Best clean alphabetic human name line (2+ chars, no digits, not card header)
        for (line in lines) {
            val candidate = sanitizeNameCandidate(line)
            if (candidate.length >= 4 &&
                !candidate.any { it.isDigit() } &&
                !isBoilerplateLine(candidate)
            ) {
                val words = candidate.split(Regex("""\s+""")).filter { it.isNotBlank() }
                if (words.size in 1..5 && words.all { it.length >= 2 }) {
                    return candidate
                }
            }
        }

        return ""
    }

    fun verifyAgainstEmployee(
        frame: CnicScanFrame,
        employee: Employee
    ): CnicMatchOutcome {
        if (!frame.cardDetected || !frame.insideGreenFrame) {
            return CnicMatchOutcome(
                matched = false,
                matchedName = "",
                matchedCnicNumber = frame.cnicNumber,
                similarityPercent = 0,
                reason = "Hold CNIC Front Side inside the Green Guide Frame"
            )
        }

        val parsed = if (frame.rawOcrText.isNotBlank()) {
            parseFrontOcrText(frame.rawOcrText, frame.insideGreenFrame)
        } else {
            CnicOcrParseResult(
                isCnicCardDetected = frame.cardDetected,
                cnicNumber = frame.cnicNumber,
                extractedName = frame.extractedName,
                confidenceCues = if (frame.extractedName.isNotBlank()) 2 else 0,
                rawText = ""
            )
        }

        val observedCnic = frame.cnicNumber.ifBlank { parsed.cnicNumber }
        val observedName = frame.extractedName.ifBlank { parsed.extractedName }

        // 1. Check stored CNIC number if BOTH employee profile and scanned card provide 13 digits
        val registeredCnicDigits = digitsOnly(employee.cnicNumber)
        val scannedCnicDigits = digitsOnly(observedCnic)
        if (registeredCnicDigits.length == 13 && scannedCnicDigits.length == 13) {
            val digitOverlap = registeredCnicDigits.zip(scannedCnicDigits).count { (a, b) -> a == b }
            if (registeredCnicDigits == scannedCnicDigits) {
                val displayName = observedName.ifBlank {
                    candidateNamesFor(employee).firstOrNull().orEmpty()
                }
                return CnicMatchOutcome(
                    matched = true,
                    matchedName = displayName,
                    matchedCnicNumber = observedCnic,
                    similarityPercent = 100,
                    reason = "CNIC number & identity verified"
                )
            } else if (digitOverlap < 11) {
                return CnicMatchOutcome(
                    matched = false,
                    matchedName = observedName,
                    matchedCnicNumber = observedCnic,
                    similarityPercent = (digitOverlap * 100) / 13,
                    reason = "CNIC mismatch ($observedCnic) does not belong to ${employee.name}"
                )
            }
        }

        // 2. Match employee's name (supports mixed Urdu/English formatted names)
        val candidates = candidateNamesFor(employee)
        if (candidates.isEmpty()) {
            return CnicMatchOutcome(
                matched = false,
                matchedName = observedName,
                matchedCnicNumber = observedCnic,
                similarityPercent = 0,
                reason = "Employee profile name unavailable for CNIC comparison"
            )
        }

        val normalizedRaw = normalizeAlpha(frame.rawOcrText.ifBlank { observedName })
        var bestScore = 0
        var bestCandidate = candidates.first()

        for (candidate in candidates) {
            val scoreFromExtracted = if (observedName.isNotBlank()) {
                computeNameSimilarityPercent(candidate, observedName)
            } else {
                0
            }
            val scoreFromRawTokens = computeTokenPresenceScore(candidate, normalizedRaw)
            val candidateScore = max(scoreFromExtracted, scoreFromRawTokens)
            if (candidateScore > bestScore) {
                bestScore = candidateScore
                bestCandidate = candidate
            }
        }

        val matched = bestScore >= NAME_MATCH_THRESHOLD_PERCENT
        return CnicMatchOutcome(
            matched = matched,
            matchedName = observedName.ifBlank { if (matched) bestCandidate else "" },
            matchedCnicNumber = observedCnic,
            similarityPercent = bestScore,
            reason = if (matched) {
                "CNIC verified ($bestScore% match: ${observedName.ifBlank { bestCandidate }})"
            } else {
                "CNIC name (${observedName.ifBlank { "unreadable" }}) did not match $bestCandidate ($bestScore%)"
            }
        )
    }

    internal fun candidateNamesFor(employee: Employee): List<String> {
        val rawNames = mutableListOf<String>()
        if (employee.cnicName.isNotBlank()) {
            rawNames += employee.cnicName
        }
        val cleanProfileName = BidiTextHelper.stripBidiControls(employee.name).trim()
        parenEnglishRegex.find(cleanProfileName)?.groupValues?.getOrNull(1)?.let { insideParens ->
            if (insideParens.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                rawNames += insideParens.trim()
            }
        }
        val withoutParens = parenEnglishRegex.replace(cleanProfileName, " ").trim()
        if (withoutParens.isNotBlank()) {
            rawNames += withoutParens
        }
        if (cleanProfileName.isNotBlank()) {
            rawNames += cleanProfileName
        }
        return rawNames.map { normalizeAlpha(it) }.filter { it.length >= 2 }.distinct()
    }

    private fun computeTokenPresenceScore(candidateNormalized: String, rawOcrNormalized: String): Int {
        if (candidateNormalized.isBlank() || rawOcrNormalized.isBlank()) return 0
        if (rawOcrNormalized.contains(candidateNormalized)) return 100
        val candidateTokens = candidateNormalized.split(" ").filter { it.length >= 2 }
        val rawTokens = rawOcrNormalized.split(" ").filter { it.length >= 2 }
        if (candidateTokens.isEmpty() || rawTokens.isEmpty()) return 0

        var totalSim = 0
        for (cTok in candidateTokens) {
            val bestTok = rawTokens.maxOfOrNull { rTok -> levenshteinSimilarityPercent(cTok, rTok) } ?: 0
            totalSim += bestTok
        }
        return (totalSim / candidateTokens.size).coerceIn(0, 100)
    }

    internal fun computeNameSimilarityPercent(targetName: String, ocrName: String): Int {
        val a = normalizeAlpha(targetName)
        val b = normalizeAlpha(ocrName)
        if (a.isBlank() || b.isBlank()) return 0
        if (a == b) return 100

        val directLev = levenshteinSimilarityPercent(a, b)
        val aTokens = a.split(" ").filter { it.length >= 2 }
        val bTokens = b.split(" ").filter { it.length >= 2 }
        if (aTokens.isEmpty() || bTokens.isEmpty()) return directLev

        val tokenAvg = aTokens.sumOf { tokA ->
            bTokens.maxOfOrNull { tokB -> levenshteinSimilarityPercent(tokA, tokB) } ?: 0
        } / aTokens.size

        return max(directLev, tokenAvg).coerceIn(0, 100)
    }

    private fun levenshteinSimilarityPercent(s1: String, s2: String): Int {
        if (s1 == s2) return 100
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 100
        val dist = levenshteinDistance(s1, s2)
        return (((maxLen - dist).toFloat() / maxLen.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) {
                    prev
                } else {
                    1 + min(prev, min(dp[j], dp[j - 1]))
                }
                prev = temp
            }
        }
        return dp[n]
    }

    private fun sanitizeNameCandidate(input: String): String {
        return input
            .replace(Regex("""[^A-Za-z\s.'-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isBoilerplateLine(candidate: String): Boolean {
        val tokens = candidate.uppercase(Locale.US).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val nonBoilerplate = tokens.count { it !in excludedCandidateTokens }
        return nonBoilerplate == 0
    }

    private fun normalizeAlpha(raw: String): String {
        return raw.lowercase(Locale.US)
            .replace(Regex("""[^a-z\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun digitsOnly(raw: String): String = raw.filter { it.isDigit() }

    const val NAME_MATCH_THRESHOLD_PERCENT = 65
}
