package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.SecurityUpdateWarning
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.data.update.VersionMetadata
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonRed
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.io.File
import java.util.Locale

sealed interface AppUpdateUiState {
    data object Hidden : AppUpdateUiState
    data class Available(val metadata: VersionMetadata) : AppUpdateUiState
    data class Downloading(
        val metadata: VersionMetadata,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AppUpdateUiState

    data class NeedsInstallPermission(
        val metadata: VersionMetadata,
        val apkFile: File
    ) : AppUpdateUiState

    data class ReadyToInstall(
        val metadata: VersionMetadata,
        val apkFile: File
    ) : AppUpdateUiState

    data class Error(
        val metadata: VersionMetadata,
        val message: String
    ) : AppUpdateUiState
}

@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDownloadUpdate: (VersionMetadata) -> Unit,
    onRequestInstallPermission: () -> Unit,
    onInstallDownloadedApk: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val metadata = when (state) {
        AppUpdateUiState.Hidden -> return
        is AppUpdateUiState.Available -> state.metadata
        is AppUpdateUiState.Downloading -> state.metadata
        is AppUpdateUiState.NeedsInstallPermission -> state.metadata
        is AppUpdateUiState.ReadyToInstall -> state.metadata
        is AppUpdateUiState.Error -> state.metadata
    }

    val isDownloading = state is AppUpdateUiState.Downloading

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = ObsidianSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.testTag("app_update_dialog"),
        icon = {
            val iconTint = when (state) {
                is AppUpdateUiState.NeedsInstallPermission -> NeonAmber
                is AppUpdateUiState.Error -> NeonRed
                is AppUpdateUiState.ReadyToInstall -> NeonGreen
                else -> NeonCyan
            }
            val iconVector = when (state) {
                is AppUpdateUiState.NeedsInstallPermission -> Icons.Rounded.SecurityUpdateWarning
                is AppUpdateUiState.ReadyToInstall -> Icons.Rounded.InstallMobile
                else -> Icons.Rounded.SystemUpdate
            }
            Icon(
                imageVector = iconVector,
                contentDescription = stringResource(R.string.app_update_available_title),
                tint = iconTint,
                modifier = Modifier.size(34.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.app_update_available_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})  →  v${metadata.latestVersionName} (Build ${metadata.latestVersionCode})",
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.app_update_keeps_data_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonGreen
                        )
                    }
                }

                if (metadata.releaseNotes.isNotBlank()) {
                    Text(
                        text = metadata.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when (state) {
                    is AppUpdateUiState.Downloading -> {
                        val sizeLabel = formatProgressBytes(state.downloadedBytes, state.totalBytes)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                                    color = NeonCyan,
                                    trackColor = ObsidianElevated,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("update_download_progress")
                                )
                            } else {
                                LinearProgressIndicator(
                                    color = NeonCyan,
                                    trackColor = ObsidianElevated,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("update_download_progress")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.downloading_update),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (state.totalBytes > 0L) {
                                        "${state.progressPercent}% ($sizeLabel)"
                                    } else {
                                        sizeLabel
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    is AppUpdateUiState.NeedsInstallPermission -> {
                        Text(
                            text = stringResource(R.string.install_permission_required_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonAmber
                        )
                    }

                    is AppUpdateUiState.ReadyToInstall -> {
                        Text(
                            text = stringResource(R.string.update_ready_to_install_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonGreen
                        )
                    }

                    is AppUpdateUiState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonRed
                        )
                    }

                    is AppUpdateUiState.Available -> Unit
                    AppUpdateUiState.Hidden -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateUiState.Available,
                is AppUpdateUiState.Error -> {
                    Button(
                        onClick = { onDownloadUpdate(metadata) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonGreen,
                            contentColor = ObsidianVoid
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("download_update_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.download_update_button),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is AppUpdateUiState.NeedsInstallPermission -> {
                    Button(
                        onClick = onRequestInstallPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonAmber,
                            contentColor = ObsidianVoid
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("grant_install_permission_button")
                    ) {
                        Text(
                            text = stringResource(R.string.allow_app_installs_button),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is AppUpdateUiState.ReadyToInstall -> {
                    Button(
                        onClick = { onInstallDownloadedApk(state.apkFile) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = ObsidianVoid
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("install_downloaded_apk_button")
                    ) {
                        Text(
                            text = stringResource(R.string.install_update_now_button),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is AppUpdateUiState.Downloading -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text(
                            text = "${state.progressPercent}%",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                AppUpdateUiState.Hidden -> Unit
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dismiss_update_button")
                ) {
                    Text(
                        text = stringResource(R.string.update_later_button),
                        color = TextSecondary
                    )
                }
            }
        }
    )
}

private fun formatProgressBytes(downloaded: Long, total: Long): String {
    val downMb = downloaded / (1024.0 * 1024.0)
    return if (total > 0L) {
        val totalMb = total / (1024.0 * 1024.0)
        String.format(Locale.US, "%.1f / %.1f MB", downMb, totalMb)
    } else {
        String.format(Locale.US, "%.1f MB", downMb)
    }
}
