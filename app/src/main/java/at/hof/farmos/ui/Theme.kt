package at.hof.farmos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2E5E3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E4BE),
    onPrimaryContainer = Color(0xFF0B2913),
    secondary = Color(0xFF7A5C2E),
    secondaryContainer = Color(0xFFF2DFBF),
    tertiary = Color(0xFF3B6470),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BD3A5),
    onPrimary = Color(0xFF0B2913),
    primaryContainer = Color(0xFF1F4A2A),
    secondary = Color(0xFFDDC195),
    tertiary = Color(0xFFA3CDDA),
)

@Composable
fun FarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
