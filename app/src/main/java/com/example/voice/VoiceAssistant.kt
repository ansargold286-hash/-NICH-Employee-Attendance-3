package com.example.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.data.model.AttendanceEventType
import com.example.data.model.BidiTextHelper
import com.example.data.model.LivenessDirection
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface VoiceSpeaker {
    fun speak(message: String, flush: Boolean = false)
    fun shutdown()
}

object NoOpVoiceSpeaker : VoiceSpeaker {
    override fun speak(message: String, flush: Boolean) = Unit
    override fun shutdown() = Unit
}

class AndroidVoiceAssistant(
    context: Context
) : VoiceSpeaker {
    private val appContext = context.applicationContext
    private val isReady = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private val utteranceCounter = AtomicInteger(0)

    @Volatile
    private var pendingMessage: String? = null

    private var tts: TextToSpeech? = null

    init {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (isReleased.get()) return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    runCatching {
                        engine?.language = Locale.US
                        engine?.setSpeechRate(1.0f)
                        engine?.setPitch(1.0f)
                    }
                    isReady.set(true)
                    val queued = pendingMessage
                    pendingMessage = null
                    if (!queued.isNullOrBlank()) {
                        speakInternal(queued, flush = true)
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init status=$status")
                }
            }
        }.onFailure { err ->
            Log.w(TAG, "TextToSpeech unavailable: ${err.message}")
        }
    }

    override fun speak(message: String, flush: Boolean) {
        val clean = message.trim()
        if (clean.isEmpty() || isReleased.get()) return
        if (!isReady.get()) {
            pendingMessage = clean
            return
        }
        speakInternal(clean, flush)
    }

    private fun speakInternal(text: String, flush: Boolean) {
        val engine = tts ?: return
        runCatching {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "nich_voice_${utteranceCounter.incrementAndGet()}"
            engine.speak(text, mode, Bundle(), id)
        }.onFailure { err ->
            Log.w(TAG, "TextToSpeech speak failed: ${err.message}")
        }
    }

    override fun shutdown() {
        if (isReleased.compareAndSet(false, true)) {
            pendingMessage = null
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = null
        }
    }

    companion object {
        private const val TAG = "AndroidVoiceAssistant"
    }
}

object VoicePromptScript {
    const val MANDATORY_EMAIL_ENFORCEMENT_PROMPT =
        "Please send the email! Without sending the email, your attendance and record will not be saved."

    const val REQUIRED_PERMISSIONS_WARNING_PROMPT =
        "Please allow required permissions!"

    fun welcomeScannerPrompt(): String =
        "Welcome to NICH Attendance 3. Please look straight into the camera and blink or smile for liveness verification."

    fun alignmentGuidePrompt(): String =
        "Please align your face inside the blue frame and keep your head level."

    fun livenessChallengePrompt(): String =
        "Liveness verification active. Please blink your eyes or smile naturally."

    fun spoofDetectedPrompt(): String =
        "Static photo or screen detected. Please blink your eyes or smile in person."

    fun clockTamperDetectedPrompt(): String =
        "Device clock mismatch detected. Attendance time is locked to the network time server."

    fun directionalLivenessPrompt(direction: LivenessDirection): String = when (direction) {
        LivenessDirection.TURN_LEFT -> "Please turn your head slightly to the left."
        LivenessDirection.TURN_RIGHT -> "Please turn your head slightly to the right."
        LivenessDirection.LOOK_UP -> "Please tilt your head slightly upward."
        LivenessDirection.LOOK_DOWN -> "Please tilt your head slightly downward."
        LivenessDirection.CENTER_VERIFY -> "Look straight into the camera and blink for verification."
    }

    fun unrecognizedFacePrompt(): String =
        "Face not recognized yet. Please step closer in clear lighting and look at the camera."

    fun verificationPrompt(employeeName: String, eventType: AttendanceEventType): String {
        val spokenName = formatSpokenName(employeeName)
        return when (eventType) {
            AttendanceEventType.CHECK_IN ->
                "Welcome, $spokenName. Check-in recorded with verified liveness and GPS location."
            AttendanceEventType.CHECK_OUT ->
                "Check-out verified for $spokenName. Generating your PDF report with live capture and GPS location. Please send the email to save and finish your record."
        }
    }

    fun emailDispatchedPrompt(openedDeviceApp: Boolean): String = if (openedDeviceApp) {
        "Opening email app to dispatch your check-out report."
    } else {
        "Check-out email and weekly report sent successfully. Attendance saved."
    }

    private fun formatSpokenName(rawName: String): String {
        val stripped = BidiTextHelper.stripBidiControls(rawName).trim()
        val parenMatch = Regex("""\(([^)]+)\)""").find(stripped)
        val asciiPreferred = if (parenMatch != null && parenMatch.groupValues[1].any { it in 'A'..'Z' || it in 'a'..'z' }) {
            parenMatch.groupValues[1].trim()
        } else {
            stripped
        }
        return asciiPreferred.ifBlank { "Employee" }
    }
}
