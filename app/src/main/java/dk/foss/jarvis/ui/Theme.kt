package dk.foss.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val JarvisDark = darkColorScheme(
    primary = Color(0xFF2E7BFF),
    onPrimary = Color(0xFFEAF6F5),
    primaryContainer = Color(0xFF13203A),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF5FF4E2),
    tertiary = Color(0xFF5FF4E2),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFEAF2F4),
    surface = Color(0xFF0A0E1A),
    onSurface = Color(0xFFEAF2F4),
    surfaceVariant = Color(0xFF13203A),
    onSurfaceVariant = Color(0x99FFFFFF),
    error = Color(0xFFFF8A6A),
    onError = Color(0xFF1A0A06),
    errorContainer = Color(0xFF3D1400),
    onErrorContainer = Color(0xFFFFB4A0),
)

val JarvisType = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Light,
        fontSize = 57.sp,
        color = Color(0xFFEAF2F4),
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        color = Color(0xFFEAF2F4),
    ),
    displaySmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        color = Color(0xFFEAF2F4),
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        color = Color(0xFFEAF2F4),
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        color = Color(0xFFEAF2F4),
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        color = Color(0xFFEAF2F4),
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        color = Color(0xFFEAF2F4),
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp,
        color = Color(0xFFEAF2F4),
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        color = Color(0xFFEAF2F4),
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp,
        color = Color(0xFFEAF2F4),
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp,
        color = Color(0xFFEAF2F4),
    ),
    bodySmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
        color = Color(0xFFEAF2F4),
    ),
    labelLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        color = Color(0xFFEAF2F4),
    ),
    labelMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        color = Color(0xFFEAF2F4),
    ),
    labelSmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = Color(0xFFEAF2F4),
    ),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisDark,
        typography = JarvisType,
        content = content,
    )
}
