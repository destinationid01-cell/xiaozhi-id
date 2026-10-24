package me.xiaozhi.androidclient.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.ui.theme.HoloColors
import me.xiaozhi.androidclient.ui.theme.OrbStatus
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Latar antariksa: bintang-bintang berkelip + nebula halus. */
@Composable
fun StarfieldBackground(modifier: Modifier = Modifier) {
    val stars = remember {
        List(90) {
            Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 2.5f + 0.8f)
        }
    }
    val twinkle by rememberInfiniteTransition(label = "twinkle").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "twinkle",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        // Nebula: gradasi ungu-biru samar
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF1B0F3A),
                    Color(0xFF050510),
                ),
                center = Offset(size.width * 0.5f, size.height * 0.25f),
                radius = size.maxDimension * 0.8f,
            ),
        )
        for ((x, y, r) in stars) {
            val alpha = (0.35f + 0.65f * twinkle * (0.5f + 0.5f * sin(x * 20))).coerceIn(0f, 1f)
            drawCircle(
                color = HoloColors.StarWhite.copy(alpha = alpha),
                radius = r,
                center = Offset(x * size.width, y * size.height),
            )
        }
    }
}

/**
 * Orb hologram: lingkaran bercahaya yang berdenyut mengikuti level mic.
 * Warna mengikuti status (siaga/listen/berpikir/bicara).
 */
@Composable
fun HoloOrb(
    status: OrbStatus,
    micLevel: Float,
    modifier: Modifier = Modifier,
    baseSize: androidx.compose.ui.unit.Dp = 110.dp,
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val baseColor = HoloColors.orbColorFor(status)
    val scale = 1f + micLevel * 0.35f + pulse * 0.06f
    Box(
        modifier = modifier
            .size(baseSize * scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.95f),
                        baseColor.copy(alpha = 0.45f),
                        HoloColors.SpaceDeep.copy(alpha = 0.15f),
                    ),
                ),
            )
            .border(2.dp, baseColor.copy(alpha = 0.7f), CircleShape),
    ) {
        // Cincin orbit
        Canvas(modifier = Modifier.fillMaxSize()) {
            val angle = pulse * 2 * PI.toFloat()
            drawArc(
                color = baseColor.copy(alpha = 0.9f),
                startAngle = angle * 57.3f,
                sweepAngle = 120f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx()),
            )
            drawArc(
                color = HoloColors.NeonPurple.copy(alpha = 0.6f),
                startAngle = -angle * 57.3f + 180f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** Header hologram: orb + nama + status, menggantikan header lama. */
@Composable
fun HoloHeader(
    state: UiState,
    onOpenSettings: () -> Unit,
) {
    val status = when {
        state.isAssistantSpeaking -> OrbStatus.SPEAKING
        state.isRecording -> OrbStatus.LISTENING
        state.isTurnActive -> OrbStatus.THINKING
        state.wakeWordEnabled -> OrbStatus.STANDBY
        else -> OrbStatus.IDLE
    }
    val statusText = when (status) {
        OrbStatus.SPEAKING -> "Berbicara"
        OrbStatus.LISTENING -> "Mendengarkan"
        OrbStatus.THINKING -> "Berpikir"
        OrbStatus.STANDBY -> "Siaga — sebut kata kunci"
        OrbStatus.IDLE -> "Nonaktif"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoloOrb(status = status, micLevel = state.micLevel, baseSize = 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Rumah Pintar",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = HoloColors.StarWhite,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HoloColors.orbColorFor(status),
                )
                if (state.wakeWords.isNotBlank()) {
                    Text(
                        text = "Kata kunci: ${state.wakeWords}",
                        style = MaterialTheme.typography.bodySmall,
                        color = HoloColors.TextDim,
                    )
                }
            }
        }
        FilledTonalIconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
        }
    }
}

/**
 * Grafik dB: bar horizontal yang naik-turun mengikuti level mic,
 * dengan penanda ambang sensitivitas.
 */
@Composable
fun DbMeter(
    micLevel: Float,
    thresholdLevel: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(HoloColors.SpaceDeep)
            .border(1.dp, HoloColors.GlassBorder, RoundedCornerShape(9.dp)),
    ) {
        // Bar level
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            HoloColors.NeonCyan,
                            HoloColors.NeonPurple,
                            HoloColors.NeonPink,
                        ),
                    ),
                )
                .fillMaxWidth(fraction = micLevel.coerceIn(0f, 1f)),
        )
        // Penanda ambang (garis vertikal putih)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val x = thresholdLevel.coerceIn(0f, 1f) * size.width
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
