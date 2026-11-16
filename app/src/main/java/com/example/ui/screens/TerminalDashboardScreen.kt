package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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

        // Short transient confirmation overlay when attendance is logged
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
    val capturedSelfieBitmap = remember(phase.liveSelfiePath) {
        val path = phase.liveSelfiePath
        if (path.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val file = File(path)
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

                if (capturedSelfieBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(2.dp, accent, CircleShape)
                            .background(ObsidianElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = capturedSelfieBitmap,
                            contentDescription = phase.employee.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    EmployeeAvatar(
                        name = phase.employee.name,
                        avatarKey = phase.employee.avatarKey,
                        size = 80.dp,
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
                    text = "${phase.eventType.displayLabel} • ${phase.timestampLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                // Check-Out ONLY: immediately display prominent email send button
                if (phase.eventType == AttendanceEventType.CHECK_OUT) {
                    val isSending = phase.emailReportStatus == EmailReportStatus.DISPATCHING
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
                            text = if (isSending) {
                                stringResource(R.string.dispatching_email_and_finishing)
                            } else {
                                stringResource(R.string.send_email_and_finish)
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = AttendanceEmailReporter.TARGET_RECIPIENT_EMAIL,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
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
