package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.AttendanceEventType
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonBlue
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.ClockSnapshot

fun avatarDrawableForKey(avatarKey: String): Int? = when (avatarKey) {
    "avatar_ansar" -> R.drawable.emp_muhammad_ansar
    "avatar_1" -> R.drawable.img_emp_avatar_1_1794571380629
    "avatar_2" -> R.drawable.img_emp_avatar_2_1794571380766
    "avatar_3" -> R.drawable.img_emp_avatar_3_1794571381051
    else -> null
}

@Composable
fun NichOfficialLogoBadge(
    size: Dp = 56.dp,
    cornerRadius: Dp = 14.dp,
    glowBrush: Brush = Brush.linearGradient(listOf(NeonCyan, NeonBlue, NeonGreen)),
    contentDescription: String? = "NICH Employee Attendance 3 official logo",
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .background(
                brush = Brush.radialGradient(
                    listOf(
                        NeonCyan.copy(alpha = 0.28f),
                        NeonBlue.copy(alpha = 0.16f),
                        Color.Transparent
                    )
                ),
                shape = shape
            )
            .clip(shape)
            .border(width = 1.5.dp, brush = glowBrush, shape = shape)
            .background(ObsidianVoid),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.nich_official_logo),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
fun EmployeeAvatar(
    name: String,
    avatarKey: String,
    size: Dp = 56.dp,
    ringColor: Color = NeonCyan
) {
    val drawableRes = avatarDrawableForKey(avatarKey)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ObsidianElevated)
            .border(2.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (drawableRes != null) {
            Image(
                painter = painterResource(id = drawableRes),
                contentDescription = "$name profile picture",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            val initials = name.trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifEmpty { "NE" }
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium,
                color = ringColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun NichBrandHeader(
    clock: ClockSnapshot,
    modifier: Modifier = Modifier
) {
    val modeColor = if (clock.eventType == AttendanceEventType.CHECK_IN) NeonGreen else NeonCyan
    val modeIcon = if (clock.eventType == AttendanceEventType.CHECK_IN) Icons.AutoMirrored.Rounded.Login else Icons.AutoMirrored.Rounded.Logout

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(
                    listOf(
                        NeonCyan.copy(alpha = 0.7f),
                        NeonBlue.copy(alpha = 0.55f),
                        NeonGreen.copy(alpha = 0.7f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = ObsidianSurface.copy(alpha = 0.96f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            NeonBlue.copy(alpha = 0.14f),
                            ObsidianSurface.copy(alpha = 0.94f),
                            NeonGreen.copy(alpha = 0.08f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                NichOfficialLogoBadge(
                    size = 56.dp,
                    cornerRadius = 14.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "NICH",
                        style = MaterialTheme.typography.labelMedium,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Employee Attendance 3",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = clock.windowLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = clock.timeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = modeColor.copy(alpha = 0.14f),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .border(1.dp, modeColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = modeIcon,
                            contentDescription = null,
                            tint = modeColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = clock.eventType.wireValue,
                            style = MaterialTheme.typography.labelSmall,
                            color = modeColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RealTimeStatusBanner(
    text: String,
    accentColor: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(targetValue = accentColor, label = "bannerColor")
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val dotAlpha = if (isPulsing) pulseAlpha else 1f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(animatedColor.copy(alpha = 0.7f), animatedColor.copy(alpha = 0.2f))
                ),
                shape = RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        color = animatedColor.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(animatedColor.copy(alpha = dotAlpha))
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = animatedColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
