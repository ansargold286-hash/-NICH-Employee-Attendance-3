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
        "PAKISTAN", "PAKISTANI", "ISLAMIC", "REPUBLIC", "OF", "NATIONAL", "IDENTITY", "CARD",
        "NAME", "FATHER", "FATHERS", "HUSBAND", "HUSBANDS", "MOTHER", "GENDER", "MALE", "FEMALE",
        "COUNTRY", "STAY", "NUMBER", "DATE", "BIRTH", "ISSUE", "EXPIRY", "SIGNATURE", "SIGN",
        "HOLDER", "HOLDERS", "THE", "NADRA", "GOVT", "GOVERNMENT", "FEDERAL", "SPECIMEN",
        "THUMB", "IMPRESSION", "CNIC", "NICOP", "POC", "SO", "DO", "WO"
    )

    private val fuzzyBoilerplateKeywords = listOf(
        "PAKISTAN", "ISLAMIC", "REPUBLIC", "NATIONAL", "IDENTITY", "SIGNATURE",
        "COUNTRY", "HUSBAND", "FATHER", "MOTHER", "HOLDER", "SPECIMEN",
        "GOVERNMENT", "FEDERAL", "EXPIRY", "BIRTH", "NUMBER", "GENDER"
    )

    private val cardHeaderRegex = Regex(
        """(?i).*(PAKISTAN|ISLAMIC\s+REPUBLIC|NATIONAL\s+IDENTITY|IDENTITY\s+NUMBER|COUNTRY\s+OF\s+STAY|DATE\s+OF\s+(BIRTH|ISSUE|EXPIRY)|HOLDER.?S?\s+SIGN).*"""
    )
    private val relationHeaderRegex = Regex("""(?i).*\b(FATHER|HUSBAND|MOTHER)\b.*""")
    private val relationMarkerRegex = Regex("""(?i)^(?:S\s*/?\s*O|D\s*/?\s*O|W\s*/?\s*O)\b.*""")

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
        val rawLines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return ""

        // Track where card structure markers appear so Pass 2 avoids header watermark/Urdu OCR noise
        var nameLabelIdx = -1
        var fatherLabelIdx = -1
        var cardHeaderEndIdx = -1

        rawLines.forEachIndexed { idx, rawLine ->
            val latinUpper = rawLine.replace(Regex("""[^A-Za-z0-9\s:/-]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .uppercase(Locale.US)
            if (latinUpper.isBlank()) return@forEachIndexed

            if (cardHeaderRegex.matches(latinUpper)) {
                cardHeaderEndIdx = max(cardHeaderEndIdx, idx)
            }
            if (fatherLabelIdx == -1 && relationHeaderRegex.matches(latinUpper)) {
                fatherLabelIdx = idx
            }
            if (nameLabelIdx == -1 &&
                !relationHeaderRegex.matches(latinUpper) &&
                Regex("""(?i)(?:^|\b)(?:HOLDER\s+)?NAME\b.*""").matches(latinUpper)
            ) {
                nameLabelIdx = idx
            }
        }

        // Pass 1: Explicitly inspect the Name line and up to 3 lines immediately below it
        // (skipping Urdu-only lines that sanitize to blank, e.g. "نام", and stopping at Father/Husband/digits)
        if (nameLabelIdx >= 0) {
            val labelLine = rawLines[nameLabelIdx]
            val sameLineAfterLabel = labelLine
                .replaceFirst(Regex("""(?i).*?\bname\b\s*[:\-/|]*\s*"""), "")
                .trim()
            val cleanedSame = sanitizeNameCandidate(sameLineAfterLabel)
            if (isValidHumanName(cleanedSame, allowSingleWord = true)) {
                return formatExtractedNameCasing(cleanedSame)
            }

            val searchEnd = min(rawLines.lastIndex, nameLabelIdx + 3)
            for (j in (nameLabelIdx + 1)..searchEnd) {
                val nextRaw = rawLines[j]
                val nextUpper = nextRaw.uppercase(Locale.US)
                if (relationHeaderRegex.matches(nextUpper) ||
                    cardHeaderRegex.matches(nextUpper) ||
                    cnicHyphenRegex.containsMatchIn(nextRaw) ||
                    nextRaw.count { it.isDigit() } >= 4
                ) {
                    break
                }
                val cleanedNext = sanitizeNameCandidate(nextRaw)
                if (cleanedNext.isBlank()) {
                    // e.g. Urdu-only line between "Name" label and English value
                    continue
                }
                if (isValidHumanName(cleanedNext, allowSingleWord = true)) {
                    return formatExtractedNameCasing(cleanedNext)
                }
            }
        }

        // Pass 2: Score all candidate lines across the card, strongly favoring multi-word names
        // situated after the card header and before the Father/Husband Name field.
        val fatherValueIdx = if (fatherLabelIdx >= 0) fatherLabelIdx + 1 else -1
        var bestCandidate = ""
        var bestScore = Int.MIN_VALUE

        rawLines.forEachIndexed { idx, rawLine ->
            if (idx == nameLabelIdx || idx == fatherLabelIdx || idx == fatherValueIdx) return@forEachIndexed
            if (rawLine.any { it.isDigit() }) return@forEachIndexed

            val candidate = sanitizeNameCandidate(rawLine)
            if (!isValidHumanName(candidate, allowSingleWord = false)) return@forEachIndexed

            val words = candidate.split(" ").filter { it.isNotBlank() }
            var score = 0
            if (words.size in 2..4) score += 60
            else if (words.size == 1) score += 10

            // Title case / mixed case ("Bilal Ahmed") is far more likely printed data than header watermark noise
            val hasMixedCase = candidate.any { it.isUpperCase() } && candidate.any { it.isLowerCase() }
            if (hasMixedCase) score += 35

            // Lines after NATIONAL IDENTITY CARD and before Father Name are prime Holder Name territory
            if (cardHeaderEndIdx in 0 until idx) score += 30
            if (fatherLabelIdx >= 0 && idx < fatherLabelIdx) score += 35
            if (fatherLabelIdx >= 0 && idx > fatherLabelIdx) score -= 25

            // Slight preference for earlier non-header lines when scores tie
            score -= idx

            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }

        if (bestCandidate.isNotBlank()) {
            return formatExtractedNameCasing(bestCandidate)
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
        val sanitizedFrameName = sanitizeNameCandidate(frame.extractedName)
            .takeIf { isValidHumanName(it, allowSingleWord = true) }
            .orEmpty()
        val observedName = parsed.extractedName.ifBlank { sanitizedFrameName }
        val canonicalEmployeeName = preferredCleanName(employee)

        // 1. Check stored CNIC number if BOTH employee profile and scanned card provide 13 digits
        val registeredCnicDigits = digitsOnly(employee.cnicNumber)
        val scannedCnicDigits = digitsOnly(observedCnic)
        if (registeredCnicDigits.length == 13 && scannedCnicDigits.length == 13) {
            val digitOverlap = registeredCnicDigits.zip(scannedCnicDigits).count { (a, b) -> a == b }
            if (registeredCnicDigits == scannedCnicDigits) {
                val resolvedMatchedName = resolveVerifiedDisplayName(
                    observedName = findBestMatchingLineName(frame.rawOcrText, observedName, employee),
                    employee = employee
                )
                return CnicMatchOutcome(
                    matched = true,
                    matchedName = resolvedMatchedName,
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
                    reason = "CNIC mismatch ($observedCnic) does not belong to ${canonicalEmployeeName.ifBlank { employee.name }}"
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
        val resolvedMatchedName = if (matched) {
            resolveVerifiedDisplayName(
                observedName = findBestMatchingLineName(frame.rawOcrText, observedName, employee),
                employee = employee
            )
        } else {
            observedName
        }

        return CnicMatchOutcome(
            matched = matched,
            matchedName = resolvedMatchedName,
            matchedCnicNumber = observedCnic,
            similarityPercent = bestScore,
            reason = if (matched) {
                "CNIC verified ($bestScore% match: $resolvedMatchedName)"
            } else {
                "CNIC name (${observedName.ifBlank { "unreadable" }}) did not match ${canonicalEmployeeName.ifBlank { bestCandidate }} ($bestScore%)"
            }
        )
    }

    /**
     * Resolves a clean, trustworthy holder name for display in Attendance UI, TTS, and PDF reports.
     * Never returns stray single letters ("a") or garbled header OCR noise ("OREAM").
     */
    fun resolveVerifiedDisplayName(observedName: String, employee: Employee): String {
        val canonical = preferredCleanName(employee)
        val cleanedObserved = sanitizeNameCandidate(observedName)
        if (cleanedObserved.isBlank() || isGarbledName(cleanedObserved)) {
            return canonical.ifBlank { employee.name }
        }

        if (canonical.isBlank()) {
            return formatExtractedNameCasing(cleanedObserved)
        }

        val simToCanonical = computeNameSimilarityPercent(canonical, cleanedObserved)
        val observedWords = cleanedObserved.split(" ").filter { it.isNotBlank() }
        val canonicalWords = sanitizeNameCandidate(canonical).split(" ").filter { it.isNotBlank() }

        return when {
            // If OCR had small spelling typos (e.g. "Muhamad Ansar" vs "Muhammad Ansar") or noise, use canonical spelling
            simToCanonical >= NAME_MATCH_THRESHOLD_PERCENT && canonicalWords.size >= observedWords.size -> canonical
            // If OCR cleanly read a fuller real name matching the employee (e.g., extra middle/last name on CNIC)
            simToCanonical >= NAME_MATCH_THRESHOLD_PERCENT && observedWords.size > canonicalWords.size ->
                formatExtractedNameCasing(cleanedObserved)
            // If observedName did not match the employee (e.g. matched via 13-digit CNIC while OCR misread "OREAM")
            else -> canonical
        }
    }

    fun isGarbledName(rawName: String): Boolean {
        val cleaned = sanitizeNameCandidate(BidiTextHelper.stripBidiControls(rawName))
        if (cleaned.isBlank()) return true
        return !isValidHumanName(cleaned, allowSingleWord = true)
    }

    private fun preferredCleanName(employee: Employee): String {
        val rawCnicName = BidiTextHelper.stripBidiControls(employee.cnicName).trim()
        parenEnglishRegex.find(rawCnicName)?.groupValues?.getOrNull(1)?.trim()?.let { inside ->
            val cleanInside = sanitizeNameCandidate(inside)
            if (cleanInside.isNotBlank() && isValidHumanName(cleanInside, allowSingleWord = true)) {
                return formatExtractedNameCasing(cleanInside)
            }
        }
        val sanitizedCnic = sanitizeNameCandidate(rawCnicName)
        if (sanitizedCnic.isNotBlank() && isValidHumanName(sanitizedCnic, allowSingleWord = true)) {
            return formatExtractedNameCasing(sanitizedCnic)
        }
        val stripped = BidiTextHelper.stripBidiControls(employee.name).trim()
        parenEnglishRegex.find(stripped)?.groupValues?.getOrNull(1)?.trim()?.let { inside ->
            if (inside.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                val cleanInside = sanitizeNameCandidate(inside)
                return if (cleanInside.isNotBlank()) formatExtractedNameCasing(cleanInside) else inside
            }
        }
        return stripped.ifBlank { employee.name }
    }

    private fun findBestMatchingLineName(
        rawOcrText: String,
        primaryObserved: String,
        employee: Employee
    ): String {
        val canonical = preferredCleanName(employee)
        if (primaryObserved.isNotBlank() && !isGarbledName(primaryObserved)) {
            if (canonical.isBlank() || computeNameSimilarityPercent(canonical, primaryObserved) >= NAME_MATCH_THRESHOLD_PERCENT) {
                return primaryObserved
            }
        }
        if (canonical.isBlank() || rawOcrText.isBlank()) return primaryObserved

        var bestLineName = primaryObserved
        var bestSim = if (primaryObserved.isNotBlank()) computeNameSimilarityPercent(canonical, primaryObserved) else 0

        for (line in rawOcrText.lines()) {
            val cleaned = sanitizeNameCandidate(line)
            if (!isValidHumanName(cleaned, allowSingleWord = true)) continue
            val sim = computeNameSimilarityPercent(canonical, cleaned)
            if (sim > bestSim) {
                bestSim = sim
                bestLineName = cleaned
            }
        }
        return bestLineName
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

    internal fun sanitizeNameCandidate(input: String): String {
        val lettersAndSpaces = input
            .replace(Regex("""(?i)\b(?:s|d|w)\s*/\s*o\b.*"""), " ")
            .replace(Regex("""[^A-Za-z\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (lettersAndSpaces.isBlank()) return ""

        val filteredWords = lettersAndSpaces.split(" ")
            .filter { token ->
                token.length >= 2 || (token.length == 1 && token in setOf("M"))
            }
        return filteredWords.joinToString(" ").trim()
    }

    internal fun isValidHumanName(candidate: String, allowSingleWord: Boolean): Boolean {
        if (candidate.isBlank()) return false
        if (relationMarkerRegex.matches(candidate)) return false
        if (isBoilerplateLine(candidate)) return false

        val words = candidate.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return false

        val letterCount = words.sumOf { word -> word.count { it in 'A'..'Z' || it in 'a'..'z' } }
        if (letterCount < 3) return false
        if (!words.any { it.length >= 3 }) return false

        // Reject any single-letter / stray 2-letter artifact or short single ALL-CAPS header noise (e.g. "OREAM")
        if (words.size == 1) {
            if (!allowSingleWord) return false
            val single = words.first()
            if (single.length < 4) return false
            val allCaps = single.all { it.isUpperCase() }
            if (allCaps && single.length < 7) return false
        } else {
            // Multi-word candidate must have at least two meaningful tokens (e.g. avoid "Dr a")
            if (words.count { it.length >= 2 } < 2) return false
        }

        // Real South-Asian / English names always contain vowels
        val hasVowel = words.all { word ->
            word.length <= 2 || word.lowercase(Locale.US).any { it in "aeiouy" }
        }
        return hasVowel
    }

    private fun formatExtractedNameCasing(cleanName: String): String {
        val words = cleanName.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val isAllUpper = words.all { token -> token.all { !it.isLetter() || it.isUpperCase() } }
        val isAllLower = words.all { token -> token.all { !it.isLetter() || it.isLowerCase() } }
        if (!isAllUpper && !isAllLower) return cleanName
        return words.joinToString(" ") { word ->
            val lower = word.lowercase(Locale.US)
            lower.replaceFirstChar { ch -> ch.titlecase(Locale.US) }
        }
    }

    private fun isBoilerplateLine(candidate: String): Boolean {
        val tokens = candidate.uppercase(Locale.US).split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        val nonBoilerplate = tokens.count { token ->
            token !in excludedCandidateTokens && !isFuzzyBoilerplateToken(token)
        }
        return nonBoilerplate == 0
    }

    private fun isFuzzyBoilerplateToken(tokenUpper: String): Boolean {
        if (tokenUpper.length < 4) return false
        return fuzzyBoilerplateKeywords.any { keyword ->
            kotlin.math.abs(tokenUpper.length - keyword.length) <= 2 &&
                levenshteinSimilarityPercent(tokenUpper, keyword) >= 78
        }
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
