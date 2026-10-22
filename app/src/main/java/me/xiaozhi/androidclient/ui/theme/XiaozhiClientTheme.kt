package me.xiaozhi.androidclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MonoBlack = Color(0xFF161616)
private val MonoDark = Color(0xFF1E1E1E)
private val MonoGray = Color(0xFF6B6B6B)
private val MonoLightGray = Color(0xFFE8E8E8)
private val MonoPaper = Color(0xFFFAFAFA)
private val Accent = Color(0xFF0F766E)
private val AccentSoft = Color(0xFFDDF3F0)

private val LightColors = lightColorScheme(
    primary = MonoBlack,
    onPrimary = Color.White,
    primaryContainer = MonoLightGray,
    onPrimaryContainer = MonoBlack,
    secondary = MonoGray,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = MonoBlack,
    tertiary = Accent,
    onTertiary = Color.White,
    tertiaryContainer = AccentSoft,
    onTertiaryContainer = Color(0xFF0B3D39),
    background = MonoPaper,
    onBackground = MonoBlack,
    surface = Color.White,
    onSurface = MonoBlack,
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = MonoGray,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = MonoBlack,
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFA8A8A8),
    onSecondary = MonoBlack,
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF5EEAD4),
    onTertiary = MonoBlack,
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFDDF3F0),
    background = MonoDark,
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2E2E2E),
    onSurfaceVariant = Color(0xFFA8A8A8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
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
        fontFamily = FontFamily.Serif,
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
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
