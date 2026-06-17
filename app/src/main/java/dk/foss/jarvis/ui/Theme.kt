package dk.foss.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisDark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF06183A),
    primaryContainer = Color(0xFF1E3A66),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF9FCAFF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE6ECFF),
    surface = Color(0xFF121829),
    onSurface = Color(0xFFE6ECFF),
    surfaceVariant = Color(0xFF1B2236),
    onSurfaceVariant = Color(0xFFAEB7CE),
    error = Color(0xFFFFB4AB),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisDark, content = content)
}
