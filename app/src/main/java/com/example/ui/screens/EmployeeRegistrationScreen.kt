package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.example.data.local.LocalRegistrationInput
import com.example.data.model.Employee
import com.example.data.model.FaceBiometricMatcher
import com.example.ui.components.EmployeeAvatar
import com.example.ui.theme.NeonAmber
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class EmployeeDraftItem(
    val draftId: String = UUID.randomUUID().toString(),
    val name: String = "",
    val department: String = "Authorized Staff",
    val photoPath: String = "",
    val faceSignature: String = ""
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && photoPath.isNotBlank() && faceSignature.isNotBlank()
}

@Composable
fun EmployeeRegistrationScreen(
    savedEmployees: List<Employee>,
    onRegisterBatch: (List<LocalRegistrationInput>) -> Unit,
    onDeleteEmployee: (String) -> Unit,
    onNavigateToScanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateToScanner() }

    val drafts = remember { mutableStateListOf(EmployeeDraftItem()) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var activeCaptureDraftIndex by remember { mutableStateOf<Int?>(null) }
    var formErrorText by remember { mutableStateOf<String?>(null) }

    val readyDrafts = drafts.filter { it.isComplete }
    val enrolledSaved = remember(savedEmployees) {
        savedEmployees.filter { FaceBiometricMatcher.parseSignature(it.faceSignature) != null }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .safeDrawingPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onNavigateToScanner,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("registration_back_to_scanner")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.open_scanner),
                            tint = NeonCyan
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.register_employees),
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Local Storage • ${enrolledSaved.size} Face Profile(s) Enrolled",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                TextButton(
                    onClick = onNavigateToScanner,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = stringResource(R.string.open_scanner),
                        color = NeonCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(
                    items = drafts,
                    key = { _, item -> item.draftId }
                ) { index, draft ->
                    EmployeeDraftCard(
                        index = index,
                        draft = draft,
                        canRemove = drafts.size > 1,
                        onNameChange = { updatedName ->
                            formErrorText = null
                            drafts[index] = drafts[index].copy(name = updatedName)
                        },
                        onDepartmentChange = { updatedDept ->
                            drafts[index] = drafts[index].copy(department = updatedDept)
                        },
                        onCapturePhotoClick = {
                            formErrorText = null
                            activeCaptureDraftIndex = index
                        },
                        onRemoveClick = {
                            drafts.removeAt(index)
                        }
                    )
                }

                // "Add Another Employee" button directly above the Submit section
                item(key = "add_another_employee_section") {
                    OutlinedButton(
                        onClick = {
                            formErrorText = null
                            drafts.add(EmployeeDraftItem())
                            val targetIdx = drafts.lastIndex
                            coroutineScope.launch {
                                listState.animateScrollToItem(targetIdx)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.5.dp, NeonCyan),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = ObsidianSurface,
                            contentColor = NeonCyan
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("add_another_employee_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.add_another_employee),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Validation helper + Submit button section
                item(key = "submit_employees_section") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!formErrorText.isNullOrBlank()) {
                            Text(
                                text = formErrorText.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonRed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Button(
                            onClick = {
                                val incompleteIndex = drafts.indexOfFirst { !it.isComplete }
                                if (readyDrafts.isEmpty() || incompleteIndex >= 0) {
                                    val idx = if (incompleteIndex >= 0) incompleteIndex + 1 else 1
                                    formErrorText =
                                        "Employee #$idx needs both a live captured face photo and full name before saving."
                                    return@Button
                                }
                                onRegisterBatch(
                                    readyDrafts.map {
                                        LocalRegistrationInput(
                                            name = it.name.trim(),
                                            department = it.department.trim().ifEmpty { "Authorized Staff" },
                                            photoPath = it.photoPath,
                                            faceSignature = it.faceSignature
                                        )
                                    }
                                )
                                drafts.clear()
                                drafts.add(EmployeeDraftItem())
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonGreen,
                                contentColor = ObsidianVoid
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("submit_registration_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Save & Register ${drafts.size} Employee(s)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Saved local profiles section
                if (enrolledSaved.isNotEmpty()) {
                    item(key = "enrolled_profiles_header") {
                        Text(
                            text = "Registered in Local Storage (${enrolledSaved.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    itemsIndexed(
                        items = enrolledSaved,
                        key = { _, emp -> "saved_${emp.id}" }
                    ) { _, emp ->
                        SavedEmployeeRow(
                            employee = emp,
                            onDelete = { onDeleteEmployee(emp.id) }
                        )
                    }
                }
            }
        }

        // CameraX Live Face Photo Capture Modal
        val captureTargetIndex = activeCaptureDraftIndex
        if (captureTargetIndex != null && captureTargetIndex in drafts.indices) {
            LiveRegistrationCameraOverlay(
                employeeIndex = captureTargetIndex + 1,
                onCaptured = { capturedPath, capturedSignature ->
                    drafts[captureTargetIndex] = drafts[captureTargetIndex].copy(
                        photoPath = capturedPath,
                        faceSignature = capturedSignature
                    )
                    activeCaptureDraftIndex = null
                },
                onDismiss = { activeCaptureDraftIndex = null }
            )
        }
    }
}

@Composable
private fun EmployeeDraftCard(
    index: Int,
    draft: EmployeeDraftItem,
    canRemove: Boolean,
    onNameChange: (String) -> Unit,
    onDepartmentChange: (String) -> Unit,
    onCapturePhotoClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    val previewBitmap = remember(draft.photoPath) {
        if (draft.photoPath.isBlank()) {
            null
        } else {
            runCatching {
                val f = File(draft.photoPath)
                if (f.exists() && f.length() > 0L) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
            }.getOrNull()
        }
    }

    val borderColor = if (draft.isComplete) NeonGreen.copy(alpha = 0.7f) else ObsidianOutline

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ObsidianSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (draft.isComplete) Icons.Rounded.CheckCircle else Icons.Rounded.Face,
                        contentDescription = null,
                        tint = if (draft.isComplete) NeonGreen else NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Employee #${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (canRemove) {
                    IconButton(
                        onClick = onRemoveClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Remove Employee #${index + 1}",
                            tint = NeonRed
                        )
                    }
                }
            }

            // Portrait thumbnail + Live Photo Capture button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(ObsidianElevated)
                        .border(
                            width = 2.dp,
                            color = if (previewBitmap != null) NeonGreen else NeonCyan.copy(alpha = 0.6f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = "Captured face preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = "No photo captured yet",
                            tint = TextMuted,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onCapturePhotoClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (previewBitmap != null) ObsidianElevated else NeonCyan,
                            contentColor = if (previewBitmap != null) NeonCyan else ObsidianVoid
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("capture_photo_button_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (previewBitmap != null) {
                                stringResource(R.string.retake_photo)
                            } else {
                                stringResource(R.string.capture_live_photo)
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = if (draft.faceSignature.isNotBlank()) {
                            "Face embedding locked for 90%+ recognition"
                        } else {
                            "Tap to capture live face photo & embedding"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (draft.faceSignature.isNotBlank()) NeonGreen else NeonAmber,
                        maxLines = 2
                    )
                }
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text("Employee Full Name *") },
                placeholder = { Text("e.g., Bilal Ahmed") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = ObsidianOutline,
                    focusedLabelColor = NeonCyan,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonCyan
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("employee_name_input_$index")
            )

            OutlinedTextField(
                value = draft.department,
                onValueChange = onDepartmentChange,
                label = { Text("Department (Optional)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = ObsidianOutline,
                    focusedLabelColor = NeonCyan,
                    unfocusedLabelColor = TextSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = NeonCyan
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("employee_dept_input_$index")
            )
        }
    }
}

@Composable
private fun SavedEmployeeRow(
    employee: Employee,
    onDelete: () -> Unit
) {
    val localBmp = remember(employee.localPhotoPath) {
        if (employee.localPhotoPath.isBlank()) {
            null
        } else {
            runCatching {
                val f = File(employee.localPhotoPath)
                if (f.exists() && f.length() > 0L) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
            }.getOrNull()
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ObsidianSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianOutline, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (localBmp != null) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, NeonGreen, CircleShape)
                        .background(ObsidianElevated)
                ) {
                    Image(
                        bitmap = localBmp,
                        contentDescription = employee.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                EmployeeAvatar(
                    name = employee.name,
                    avatarKey = employee.avatarKey,
                    size = 48.dp,
                    ringColor = NeonCyan
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = employee.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = NeonGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${employee.employeeCode} • ${employee.department}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_employee_${employee.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete ${employee.name}",
                    tint = NeonRed
                )
            }
        }
    }
}
