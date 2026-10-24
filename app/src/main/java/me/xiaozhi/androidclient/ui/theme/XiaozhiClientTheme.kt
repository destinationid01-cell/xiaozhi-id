package me.xiaozhi.androidclient.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tema Hologram Universe — selalu gelap, nuansa antariksa + neon. */
object HoloColors {
    val SpaceBlack = Color(0xFF050510)
    val SpaceDeep = Color(0xFF0A0A1E)
    val SpacePanel = Color(0xFF12122B)
    val SpacePanelTranslucent = Color(0xCC12122B)
    val NeonCyan = Color(0xFF00E5FF)
    val NeonCyanSoft = Color(0xFF66F6FF)
    val NeonPurple = Color(0xFFA855F7)
    val NeonPurpleSoft = Color(0xFFD8B4FE)
    val NeonGreen = Color(0xFF34FF9E)
    val NeonOrange = Color(0xFFFF9F43)
    val NeonPink = Color(0xFFFF4D8D)
    val StarWhite = Color(0xFFE8F4FF)
    val TextDim = Color(0xFF8B93B8)
    val GlassBorder = Color(0x3300E5FF)

    /** Warna orb berdasarkan status. */
    fun orbColorFor(status: OrbStatus): Color = when (status) {
        OrbStatus.STANDBY -> NeonCyan
        OrbStatus.LISTENING -> NeonGreen
        OrbStatus.THINKING -> NeonPurple
        OrbStatus.SPEAKING -> NeonOrange
        OrbStatus.IDLE -> TextDim
    }
}

enum class OrbStatus { STANDBY, LISTENING, THINKING, SPEAKING, IDLE }

private val HoloColorScheme = darkColorScheme(
    primary = HoloColors.NeonCyan,
    onPrimary = HoloColors.SpaceBlack,
    primaryContainer = HoloColors.SpacePanel,
    onPrimaryContainer = HoloColors.StarWhite,
    secondary = HoloColors.NeonPurple,
    onSecondary = Color.White,
    secondaryContainer = HoloColors.SpacePanel,
    onSecondaryContainer = HoloColors.StarWhite,
    tertiary = HoloColors.NeonCyanSoft,
    onTertiary = HoloColors.SpaceBlack,
    tertiaryContainer = HoloColors.SpaceDeep,
    onTertiaryContainer = HoloColors.NeonCyanSoft,
    background = HoloColors.SpaceBlack,
    onBackground = HoloColors.StarWhite,
    surface = HoloColors.SpacePanel,
    onSurface = HoloColors.StarWhite,
    surfaceVariant = HoloColors.SpaceDeep,
    onSurfaceVariant = HoloColors.TextDim,
    error = HoloColors.NeonPink,
    onError = Color.White,
    errorContainer = Color(0xFF3D0A1E),
    onErrorContainer = Color(0xFFFFD6E7),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
)

@Composable
fun XiaozhiClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HoloColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
