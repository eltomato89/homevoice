package de.homevoice.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Listening = Color(0xFF4FC3F7)
    val Speaking = Color(0xFF7C4DFF)
    val Connecting = Color(0xFFE0E0E0)
    val Error = Color(0xFFFF5252)
    val Alarm = Color(0xFFFF8A65)
}

private val colors = darkColorScheme(
    primary = Palette.Listening,
    secondary = Palette.Speaking,
    background = Color(0xFF0B0F14),
    surface = Color(0xFF141A21),
    surfaceVariant = Color(0xFF1E262F),
    error = Palette.Error,
)

@Composable
fun HomeVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
