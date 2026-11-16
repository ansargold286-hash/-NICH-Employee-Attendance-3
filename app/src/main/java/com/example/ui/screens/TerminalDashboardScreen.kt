package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.FaceAnalysisFrame
import com.example.data.model.LivenessDirection
import com.example.data.report.AttendanceEmailReporter
import com.example.data.report.EmailReportStatus
import com.example.ui.components.BiometricCameraScanner
import com.example.ui.components.EmployeeAvatar
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
    enrolledFacesCount: Int = 0,
    onOpenRegistration: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(terminalPhase) {
        if (terminalPhase is TerminalUiPhase.Ready) {
            onStartVipScan()
        }
    }

    if (terminalPhase is TerminalUiPhase.Failure ||
        (terminalPhase is TerminalUiPhase.Verified && terminalPhase.eventType == AttendanceEventType.CHECK_OUT)
    ) {
        BackHandler { onResetTerminal() }
    }

    val activeSteps = (terminalPhase as? TerminalUiPhase.Scanning)?.livenessSteps
        ?: listOf(LivenessDirection.CENTER_VERIFY)
    val activeStepIndex = (terminalPhase as? TerminalUiPhase.Scanning)?.currentLivenessStepIndex ?: 0

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
            modifier = Modifier.fillMaxSize()
        )

        // Subtle live quality / matching guidance pill at the top center
        val qualityHint = (terminalPhase as? TerminalUiPhase.Scanning)?.qualityHint
        if (!qualityHint.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ObsidianSurface.copy(alpha = 0.88f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 18.dp, start = 24.dp, end = 24.dp)
                    .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = qualityHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Bottom-left action pill to open Multi-Employee Local Registration Form
        if (onOpenRegistration != null && terminalPhase is TerminalUiPhase.Scanning) {
            OutlinedButton(
                onClick = onOpenRegistration,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.2.dp, NeonCyan.copy(alpha = 0.72f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = ObsidianSurface.copy(alpha = 0.88f),
                    contentColor = NeonCyan
                ),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, bottom = 22.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("open_registration_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (enrolledFacesCount > 0) {
                        "Register Staff ($enrolledFacesCount)"
                    } else {
                        "Register Staff"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

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
                    onSendCheckOutEmail = onSendCheckOutEmail
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
    onSendCheckOutEmail: () -> Unit
) {
    val accent = if (phase.eventType == AttendanceEventType.CHECK_IN) NeonGreen else NeonCyan
    val registeredPhotoBitmap = remember(phase.employee.localPhotoPath, phase.liveSelfiePath) {
        val registeredPath = phase.employee.localPhotoPath.takeIf { it.isNotBlank() }
        val chosenPath = registeredPath ?: phase.liveSelfiePath
        if (chosenPath.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val file = File(chosenPath)
                if (file.exists() && file.length() > 0L) {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid.copy(alpha = 0.56f))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp)
            .testTag("verification_successful_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface.copy(alpha = 0.96f),
            tonalElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .border(2.dp, accent, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
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
                        fontWeight = FontWeight.Bold
                    )
                }

                if (registeredPhotoBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .border(2.dp, accent, CircleShape)
                            .background(ObsidianElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = registeredPhotoBitmap,
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

                // Conditional Email Notification: shown & triggered ONLY on Check-Out
                if (phase.eventType == AttendanceEventType.CHECK_OUT) {
                    val isSending = phase.emailReportStatus == EmailReportStatus.DISPATCHING
                    val isSent = phase.emailReportStatus == EmailReportStatus.SENT
                    Button(
                        onClick = onSendCheckOutEmail,
                        enabled = !isSending,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonGreen,
                            contentColor = ObsidianVoid,
                            disabledContainerColor = NeonGreen.copy(alpha = 0.5f),
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
                                isSent -> "Email Sent • Finish"
                                isSending -> stringResource(R.string.dispatching_email_and_finishing)
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
