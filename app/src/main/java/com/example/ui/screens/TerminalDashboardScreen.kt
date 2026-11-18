package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.CnicScanFrame
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.LivenessDirection
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.ui.components.BiometricCameraScanner
import com.example.ui.components.BiometricScannerMode
import com.example.ui.components.EmployeeAvatar
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.TerminalUiPhase
import java.io.File

@Composable
fun TerminalDashboardScreen(
    terminalPhase: TerminalUiPhase,
    onStartVipScan: () -> Unit,
    onCameraInitialized: () -> Unit,
    onCameraInitFailed: (String) -> Unit,
    onFrameAnalyzed: (FaceAnalysisFrame) -> Unit,
    onSendCheckOutEmail: () -> Unit,
    onResetTerminal: () -> Unit,
    onAttemptSkipCheckOutEmail: () -> Unit = {},
    networkTimeLabel: String = "NTP Network Time",
    modifier: Modifier = Modifier,
    onCnicFrameAnalyzed: (CnicScanFrame) -> Unit = {},
    onCancelCnicScan: () -> Unit = {}
) {
    LaunchedEffect(terminalPhase) {
        if (terminalPhase is TerminalUiPhase.Ready) {
            onStartVipScan()
        }
    }

    if (terminalPhase is TerminalUiPhase.Failure) {
        BackHandler { onResetTerminal() }
    } else if (terminalPhase is TerminalUiPhase.CnicScanning) {
        BackHandler { onCancelCnicScan() }
    } else if (terminalPhase is TerminalUiPhase.Verified &&
        terminalPhase.eventType == AttendanceEventType.CHECK_OUT
    ) {
        BackHandler {
            if (terminalPhase.isCheckOutEmailCompleted) {
                onResetTerminal()
            } else {
                onAttemptSkipCheckOutEmail()
            }
        }
    }

    val activeSteps = (terminalPhase as? TerminalUiPhase.Scanning)?.livenessSteps
        ?: listOf(LivenessDirection.CENTER_VERIFY)
    val activeStepIndex = (terminalPhase as? TerminalUiPhase.Scanning)?.currentLivenessStepIndex ?: 0
    val cnicPhase = terminalPhase as? TerminalUiPhase.CnicScanning
    val scannerMode = if (cnicPhase != null) {
        BiometricScannerMode.REAR_CNIC_SCAN
    } else {
        BiometricScannerMode.FRONT_FACE_LIVENESS
    }
    val cnicAligned = cnicPhase?.let { it.cardDetected && it.insideGreenFrame } ?: false

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // Keep full-screen CameraX viewfinder permanently live so transitions remain zero-flicker
        BiometricCameraScanner(
            livenessSteps = activeSteps,
            currentLivenessStepIndex = activeStepIndex,
            onCameraInitialized = onCameraInitialized,
            onCameraInitFailed = onCameraInitFailed,
            onFrameAnalyzed = onFrameAnalyzed,
            modifier = Modifier.fillMaxSize(),
            scannerMode = scannerMode,
            cnicAligned = cnicAligned,
            onCnicFrameAnalyzed = onCnicFrameAnalyzed
        )

        // Subtle live AI voice, anti-spoofing liveness, Step-B CNIC guidance & NTP time lock pill at the top center
        val scanningPhase = terminalPhase as? TerminalUiPhase.Scanning
        if (scanningPhase != null) {
            val qualityHint = scanningPhase.qualityHint
            val pillText = qualityHint?.takeIf { it.isNotBlank() }
                ?: if (scanningPhase.livenessVerified) {
                    "Liveness Verified • $networkTimeLabel"
                } else {
                    stringResource(R.string.liveness_blink_or_smile_hint)
                }
            val pillBorderColor = when {
                scanningPhase.spoofSuspected -> NeonRed.copy(alpha = 0.85f)
                scanningPhase.livenessVerified -> NeonGreen.copy(alpha = 0.75f)
                else -> NeonCyan.copy(alpha = 0.45f)
            }
            val pillIconTint = when {
                scanningPhase.spoofSuspected -> NeonRed
                scanningPhase.livenessVerified -> NeonGreen
                else -> NeonCyan
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurface.copy(alpha = 0.88f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 18.dp, start = 24.dp, end = 24.dp)
                    .border(1.dp, pillBorderColor, RoundedCornerShape(14.dp))
                    .testTag("scanner_voice_guidance_pill")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = stringResource(R.string.voice_assistant_active),
                            tint = pillIconTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = pillText,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = networkTimeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("network_time_lock_badge")
                    )
                    Text(
                        text = "NICH Attendance 3 • v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("app_version_badge")
                    )
                }
            }
        } else if (cnicPhase != null) {
            val cnicBorder = if (cnicPhase.cardDetected && cnicPhase.insideGreenFrame) {
                NeonGreen.copy(alpha = 0.85f)
            } else {
                NeonCyan.copy(alpha = 0.65f)
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = ObsidianSurface.copy(alpha = 0.90f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 18.dp, start = 24.dp, end = 24.dp)
                    .border(1.5.dp, cnicBorder, RoundedCornerShape(14.dp))
                    .testTag("cnic_step_guidance_pill")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Step A Face Verified: ${cnicPhase.employee.name} (${cnicPhase.faceConfidenceScore}%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonGreen,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = cnicPhase.qualityHint?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.cnic_green_frame_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    if (cnicPhase.detectedCnicName.isNotBlank() || cnicPhase.detectedCnicNumber.isNotBlank()) {
                        Text(
                            text = listOf(cnicPhase.detectedCnicName, cnicPhase.detectedCnicNumber)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan,
                            textAlign = TextAlign.Center
                        )
                    }
                    Button(
                        onClick = onCancelCnicScan,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ObsidianElevated,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("cancel_cnic_scan_button")
                    ) {
                        Text(
                            text = "Re-scan Face",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Bottom Register/Add Employee button intentionally omitted after first-time setup.
        // Transient confirmation overlay when attendance is logged
        AnimatedVisibility(
            visible = terminalPhase is TerminalUiPhase.Verified,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220)),
            modifier = Modifier.fillMaxSize()
        ) {
            val verified = terminalPhase as? TerminalUiPhase.Verified
            if (verified != null) {
                VerificationSuccessOverlay(
                    phase = verified,
                    onSendCheckOutEmail = onSendCheckOutEmail,
                    onAttemptDismiss = onAttemptSkipCheckOutEmail
                )
            }
        }

        // Minimal hardware-unavailable fallback overlay if CameraX failed to bind
        AnimatedVisibility(
            visible = terminalPhase is TerminalUiPhase.Failure,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            val failure = terminalPhase as? TerminalUiPhase.Failure
            if (failure != null) {
                CameraHardwareNoticeOverlay(
                    phase = failure,
                    onRetry = onStartVipScan
                )
            }
        }
    }
}

@Composable
private fun VerificationSuccessOverlay(
    phase: TerminalUiPhase.Verified,
    onSendCheckOutEmail: () -> Unit,
    onAttemptDismiss: () -> Unit
) {
    val accent = if (phase.eventType == AttendanceEventType.CHECK_IN) NeonGreen else NeonCyan
    val registeredPhotoBitmap = remember(phase.employee.localPhotoPath) {
        val registeredPath = phase.employee.localPhotoPath.takeIf { it.isNotBlank() }
        if (registeredPath.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val file = File(registeredPath)
                if (file.exists() && file.length() > 0L) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } else {
                    null
                }
            }.getOrNull()
        }
    }
    val liveSelfieBitmap = remember(phase.liveSelfiePath) {
        val selfiePath = phase.liveSelfiePath.takeIf { !it.isNullOrBlank() }
        if (selfiePath.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val file = File(selfiePath)
                if (file.exists() && file.length() > 0L) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    val cardBorderColor = if (phase.emailEnforcementWarningActive || phase.isPersistentLock) NeonRed else accent

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.62f))
            .testTag("verification_successful_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(phase.eventType, phase.isCheckOutEmailCompleted) {
                    detectTapGestures {
                        if (phase.eventType == AttendanceEventType.CHECK_OUT && !phase.isCheckOutEmailCompleted) {
                            onAttemptDismiss()
                        }
                    }
                }
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface.copy(alpha = 0.97f),
            tonalElevation = 10.dp,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .pointerInput(Unit) {
                    detectTapGestures { /* Consume taps inside the card without merging semantics descendants */ }
                }
                .border(2.dp, cardBorderColor, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = stringResource(R.string.verification_successful),
                            tint = accent,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.verification_successful),
                            style = MaterialTheme.typography.titleLarge,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (phase.eventType == AttendanceEventType.CHECK_OUT && !phase.isCheckOutEmailCompleted) {
                        IconButton(
                            onClick = onAttemptDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("close_checkout_overlay_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.email_enforcement_title),
                                tint = TextSecondary
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val primaryBitmap = liveSelfieBitmap ?: registeredPhotoBitmap
                    if (primaryBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(CircleShape)
                                .border(2.dp, accent, CircleShape)
                                .background(ObsidianElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = primaryBitmap,
                                contentDescription = phase.employee.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        EmployeeAvatar(
                            name = phase.employee.name,
                            avatarKey = phase.employee.avatarKey,
                            size = 86.dp,
                            ringColor = accent
                        )
                    }
                    if (liveSelfieBitmap != null && registeredPhotoBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, TextSecondary, CircleShape)
                                .background(ObsidianElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = registeredPhotoBitmap,
                                contentDescription = "Registered profile photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Text(
                    text = phase.employee.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${phase.eventType.displayLabel} (${phase.confidenceScore}% Match) • ${phase.timestampLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (phase.gpsCoordinates.isNotBlank() ||
                    phase.locationAddress.isNotBlank() ||
                    phase.timeSourceLabel.isNotBlank() ||
                    phase.matchedCnicNumber.isNotBlank() ||
                    phase.matchedCnicName.isNotBlank()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ObsidianElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .testTag("verified_gps_ntp_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (phase.matchedCnicNumber.isNotBlank() || phase.matchedCnicName.isNotBlank()) {
                                val cnicLabel = listOf(phase.matchedCnicName, phase.matchedCnicNumber)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" • ")
                                Text(
                                    text = "CNIC Verified: $cnicLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("verified_cnic_badge")
                                )
                            }
                            if (phase.gpsCoordinates.isNotBlank()) {
                                Text(
                                    text = "GPS: ${phase.gpsCoordinates}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan
                                )
                            }
                            if (phase.locationAddress.isNotBlank()) {
                                Text(
                                    text = "Location: ${phase.locationAddress}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (phase.timeSourceLabel.isNotBlank()) {
                                Text(
                                    text = "Time Lock: ${phase.timeSourceLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                if (phase.isBatchMode) {
                    val batchColor = if (phase.batchAllCompleted) NeonGreen else NeonAmber
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = batchColor.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, batchColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .testTag("batch_progress_summary_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Team Batch Cycle: ${phase.batchCompletedCount}/${phase.batchTotalCount} Completed",
                                style = MaterialTheme.typography.labelMedium,
                                color = batchColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (phase.batchAllCompleted) {
                                    "All registered staff finished Check-In & Check-Out. Consolidated Batch PDF ready."
                                } else {
                                    val pendingText = phase.batchPendingEmployeeNames.joinToString(", ")
                                    if (pendingText.isNotBlank()) {
                                        "Waiting for: $pendingText. ${stringResource(R.string.batch_waiting_others_hint)}"
                                    } else {
                                        stringResource(R.string.batch_waiting_others_hint)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // Conditional Email Notification + Mandatory Blocking Enforcement: shown & triggered ONLY on Check-Out
                if (phase.eventType == AttendanceEventType.CHECK_OUT) {
                    val isSending = phase.emailReportStatus == EmailReportStatus.DISPATCHING
                    val isSent = phase.emailReportStatus == EmailReportStatus.SENT
                    val countdownSec = phase.emailAutoUnlockSecondsRemaining
                    val batchReadyToSend = !phase.isBatchMode || phase.batchAllCompleted
                    val showBlockingBanner = (phase.emailEnforcementWarningActive || phase.isPersistentLock) &&
                        !phase.isCheckOutEmailCompleted &&
                        countdownSec == null

                    if (countdownSec != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = NeonCyan.copy(alpha = 0.14f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, NeonCyan, RoundedCornerShape(12.dp))
                                .testTag("pending_email_unlock_countdown")
                        ) {
                            Text(
                                text = "Email triggered • Auto-clearing lock & unlocking scanner in ${countdownSec}s...",
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                    } else if (showBlockingBanner) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = NeonRed.copy(alpha = 0.16f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, NeonRed, RoundedCornerShape(14.dp))
                                .testTag("email_enforcement_warning_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = stringResource(
                                        if (phase.isPersistentLock) R.string.pending_checkout_lock_title
                                        else R.string.email_enforcement_title
                                    ),
                                    tint = NeonRed,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(
                                            if (phase.isPersistentLock) R.string.pending_checkout_lock_title
                                            else R.string.email_enforcement_title
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = NeonRed,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = phase.emailEnforcementMessage
                                            ?: stringResource(
                                                if (phase.isPersistentLock) R.string.pending_checkout_lock_message
                                                else R.string.email_enforcement_blocking_message
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (phase.isPersistentLock) {
                                        Text(
                                            text = stringResource(R.string.offline_lock_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonCyan,
                                            modifier = Modifier.testTag("offline_lock_saved_badge")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onSendCheckOutEmail,
                        enabled = !isSending && countdownSec == null && batchReadyToSend,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonGreen,
                            contentColor = ObsidianVoid,
                            disabledContainerColor = NeonGreen.copy(alpha = 0.35f),
                            disabledContentColor = ObsidianVoid.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("send_checkout_email_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                countdownSec != null -> "Unlocking in ${countdownSec}s..."
                                !batchReadyToSend -> "Batch Waiting (${phase.batchCompletedCount}/${phase.batchTotalCount})"
                                isSent -> "Email Sent • Finish"
                                isSending -> stringResource(R.string.dispatching_email_and_finishing)
                                phase.isBatchMode -> stringResource(R.string.send_batch_email_and_finish)
                                phase.isPersistentLock -> stringResource(R.string.retry_send_checkout_email)
                                else -> stringResource(R.string.send_email_and_finish)
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${phase.emailReportStatus.label} (${AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraHardwareNoticeOverlay(
    phase: TerminalUiPhase.Failure,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.78f))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = ObsidianSurface.copy(alpha = 0.96f),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp)
                .border(1.5.dp, NeonRed.copy(alpha = 0.8f), RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = phase.title,
                    tint = NeonRed,
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = phase.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = phase.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = ObsidianVoid
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = stringResource(R.string.grant_camera_permission),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
