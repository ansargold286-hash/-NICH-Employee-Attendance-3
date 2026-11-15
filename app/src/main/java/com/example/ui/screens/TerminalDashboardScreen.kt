package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.data.model.FaceAnalysisFrame
import com.example.data.report.EmailReportStatus
import com.example.ui.components.BiometricCameraScanner
import com.example.ui.components.EmployeeAvatar
import com.example.ui.components.NichBrandHeader
import com.example.ui.components.NichOfficialLogoBadge
import com.example.ui.components.RealTimeStatusBanner
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.ClockSnapshot
import com.example.ui.viewmodel.TerminalUiPhase
import java.io.File

@Composable
fun TerminalDashboardScreen(
    terminalPhase: TerminalUiPhase,
    clock: ClockSnapshot,
    onStartVipScan: () -> Unit,
    onCameraInitialized: () -> Unit,
    onCameraInitFailed: (String) -> Unit,
    onFrameAnalyzed: (FaceAnalysisFrame) -> Unit,
    onSendEmailAndFinish: () -> Unit,
    onResetTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (terminalPhase !is TerminalUiPhase.Ready) {
        BackHandler { onResetTerminal() }
    }

    val (bannerText, bannerColor, isPulsing) = when (terminalPhase) {
        is TerminalUiPhase.Ready -> Triple(
            "STRICT BIOMETRIC TERMINAL READY • ${clock.eventType.displayLabel} MODE",
            NeonGreen,
            false
        )
        is TerminalUiPhase.Scanning -> Triple(
            terminalPhase.statusHeadline,
            if (terminalPhase.attempt >= 2) NeonAmber else NeonCyan,
            true
        )
        is TerminalUiPhase.Verified -> {
            val label = if (terminalPhase.eventType == AttendanceEventType.CHECK_IN) {
                "CHECK-IN VERIFIED • ${terminalPhase.employee.name.uppercase()}"
            } else {
                "CHECK-OUT VERIFIED • ${terminalPhase.employee.name.uppercase()}"
            }
            Triple(label, NeonGreen, false)
        }
        is TerminalUiPhase.Failure -> Triple(
            "${terminalPhase.title} • ${terminalPhase.message}",
            NeonRed,
            false
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NichBrandHeader(
            clock = clock,
            modifier = Modifier.widthIn(max = 720.dp)
        )

        RealTimeStatusBanner(
            text = bannerText,
            accentColor = bannerColor,
            isPulsing = isPulsing,
            modifier = Modifier.widthIn(max = 720.dp)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            bannerColor.copy(alpha = 0.85f),
                            NeonBlue.copy(alpha = 0.45f),
                            NeonCyan.copy(alpha = 0.75f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface.copy(alpha = 0.96f),
            tonalElevation = 8.dp
        ) {
            // Key by phase class so live frame updates inside Scanning never re-create CameraX PreviewView
            AnimatedContent(
                targetState = terminalPhase,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
                contentKey = { phase -> phase::class },
                label = "vipPanelContent"
            ) { phase ->
                when (phase) {
                    is TerminalUiPhase.Ready -> ReadyPanelContent(
                        clock = clock,
                        onStartVipScan = onStartVipScan
                    )
                    is TerminalUiPhase.Scanning -> ScanningPanelContent(
                        phase = phase,
                        onCameraInitialized = onCameraInitialized,
                        onCameraInitFailed = onCameraInitFailed,
                        onFrameAnalyzed = onFrameAnalyzed,
                        onCancel = onResetTerminal
                    )
                    is TerminalUiPhase.Verified -> VerifiedPanelContent(
                        phase = phase,
                        onSendEmailAndFinish = onSendEmailAndFinish
                    )
                    is TerminalUiPhase.Failure -> FailurePanelContent(
                        phase = phase,
                        onRetry = onStartVipScan,
                        onReset = onResetTerminal
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyPanelContent(
    clock: ClockSnapshot,
    onStartVipScan: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vipButtonGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VIP BIOMETRIC TERMINAL",
                style = MaterialTheme.typography.labelLarge,
                color = NeonCyan
            )
            Text(
                text = clock.dateKey,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, ObsidianOutline, RoundedCornerShape(18.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_nich_hero_1794571379117),
                contentDescription = "Biometric facial recognition terminal display",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                ObsidianVoid.copy(alpha = 0.88f)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = "LIVE LIVENESS & BIOMETRIC TERMINAL",
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonGreen
                )
                Text(
                    text = "Tap VIP Attendance to complete live directional liveness, capture selfie verification, and log attendance.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
        }

        Surface(
            onClick = onStartVipScan,
            shape = RoundedCornerShape(20.dp),
            color = ObsidianVoid,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .border(
                    width = 2.dp,
                    brush = Brush.horizontalGradient(
                        listOf(
                            NeonCyan.copy(alpha = glowAlpha),
                            NeonGreen.copy(alpha = glowAlpha),
                            NeonBlue.copy(alpha = glowAlpha)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .minimumInteractiveComponentSize()
                .testTag("vip_attendance_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                NeonBlue.copy(alpha = 0.24f),
                                ObsidianElevated.copy(alpha = 0.95f),
                                NeonGreen.copy(alpha = 0.22f)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    NichOfficialLogoBadge(
                        size = 52.dp,
                        cornerRadius = 12.dp,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "VIP ATTENDANCE",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "OPEN LIVE CAMERA FACE SCAN",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonGreen,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = NeonGreen,
                    modifier = Modifier
                        .size(44.dp)
                        .border(1.dp, NeonCyan, CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Face,
                            contentDescription = null,
                            tint = ObsidianVoid,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningPanelContent(
    phase: TerminalUiPhase.Scanning,
    onCameraInitialized: () -> Unit,
    onCameraInitFailed: (String) -> Unit,
    onFrameAnalyzed: (FaceAnalysisFrame) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    !phase.cameraReady -> "STARTING CAMERAX BIOMETRIC ENGINE..."
                    phase.attempt >= 2 -> "AUTO-RETRY BIOMETRIC SCAN (2/2)"
                    else -> "LIVE LIVENESS & BIOMETRIC SCAN"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (phase.attempt >= 2) NeonAmber else NeonCyan
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel biometric scan",
                    tint = TextSecondary
                )
            }
        }

        BiometricCameraScanner(
            attemptNumber = phase.attempt,
            cameraReady = phase.cameraReady,
            faceDetected = phase.faceDetected,
            eyesLocked = phase.eyesLocked,
            noseMustacheLocked = phase.noseMustacheLocked,
            jawBeardLocked = phase.jawBeardLocked,
            livenessSteps = phase.livenessSteps,
            currentLivenessStepIndex = phase.currentLivenessStepIndex,
            qualityHint = phase.qualityHint,
            onCameraInitialized = onCameraInitialized,
            onCameraInitFailed = onCameraInitFailed,
            onFrameAnalyzed = onFrameAnalyzed
        )
    }
}

@Composable
private fun VerifiedPanelContent(
    phase: TerminalUiPhase.Verified,
    onSendEmailAndFinish: () -> Unit
) {
    val accent = if (phase.eventType == AttendanceEventType.CHECK_IN) NeonGreen else NeonCyan

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NichOfficialLogoBadge(
                    size = 32.dp,
                    cornerRadius = 8.dp,
                    contentDescription = "NICH Employee Attendance 3 logo"
                )
                Text(
                    text = "NICH Employee Attendance 3",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                color = accent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.border(1.dp, accent, RoundedCornerShape(999.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${phase.eventType.displayLabel} VERIFIED",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                }
            }
        }

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

        if (capturedSelfieBitmap != null) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .border(2.5.dp, accent, CircleShape)
                    .background(ObsidianElevated),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = capturedSelfieBitmap,
                    contentDescription = "Live verified selfie of ${phase.employee.name}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            EmployeeAvatar(
                name = phase.employee.name,
                avatarKey = phase.employee.avatarKey,
                size = 96.dp,
                ringColor = accent
            )
        }

        Text(
            text = phase.employee.name,
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )

        Text(
            text = phase.employee.roleTitle,
            style = MaterialTheme.typography.bodyLarge,
            color = NeonCyan
        )

        Surface(
            color = ObsidianElevated,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ObsidianOutline, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VerificationDetailRow("EMPLOYEE ID", phase.employee.employeeCode, NeonGreen)
                VerificationDetailRow("DEPARTMENT", phase.employee.department, TextPrimary)
                VerificationDetailRow("METHOD", phase.verificationMethod.displayLabel, NeonCyan)
                VerificationDetailRow(
                    label = "LIVE SELFIE",
                    value = if (capturedSelfieBitmap != null) {
                        "Captured & Embedded in PDF"
                    } else {
                        "Liveness Check Passed"
                    },
                    valueColor = NeonGreen
                )
                VerificationDetailRow(
                    label = "SENDER ACCOUNT",
                    value = phase.deviceSenderAccount ?: "Logged-In Device Gmail",
                    valueColor = NeonCyan
                )
                VerificationDetailRow("TIMESTAMP", phase.timestampLabel, TextPrimary)
                VerificationDetailRow(
                    label = "SYNC STATUS",
                    value = if (phase.syncedToCloud) "Cloud Firestore Live" else "Saved Offline (Queued)",
                    valueColor = if (phase.syncedToCloud) NeonGreen else NeonAmber
                )
                val emailColor = when (phase.emailReportStatus) {
                    EmailReportStatus.SENT -> NeonGreen
                    EmailReportStatus.DISPATCHING -> NeonCyan
                    EmailReportStatus.QUEUED_OFFLINE,
                    EmailReportStatus.NEEDS_CONFIG -> NeonAmber
                }
                VerificationDetailRow(
                    label = "PDF EMAIL REPORT",
                    value = phase.emailReportStatus.label,
                    valueColor = emailColor
                )
            }
        }

        // Single prominent green CTA replacing 'DONE • NEXT': dispatches PDF via device Gmail & returns to main dashboard
        Button(
            onClick = onSendEmailAndFinish,
            colors = ButtonDefaults.buttonColors(
                containerColor = NeonGreen,
                contentColor = ObsidianVoid
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .minimumInteractiveComponentSize()
                .testTag("send_email_finish_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.send_email_and_finish),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VerificationDetailRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FailurePanelContent(
    phase: TerminalUiPhase.Failure,
    onRetry: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = NeonRed,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = phase.title,
            style = MaterialTheme.typography.titleLarge,
            color = NeonRed,
            textAlign = TextAlign.Center
        )
        Text(
            text = phase.message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
            ) {
                Text("Dismiss")
            }
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = ObsidianVoid
                ),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
            ) {
                Text("Retry Scan", fontWeight = FontWeight.Bold)
            }
        }
    }
}
