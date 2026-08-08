package org.apptwin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AppTwinDarkColors = darkColorScheme(
    primary = Color(0xFF9DCAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF144B73),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFB7C8DD),
    onSecondary = Color(0xFF223240),
    secondaryContainer = Color(0xFF384956),
    onSecondaryContainer = Color(0xFFD3E4F5),
    tertiary = Color(0xFFCFC0FF),
    onTertiary = Color(0xFF37275F),
    tertiaryContainer = Color(0xFF4E3E77),
    onTertiaryContainer = Color(0xFFE8DDFF),
    background = Color(0xFF0C1117),
    onBackground = Color(0xFFDDE3EA),
    surface = Color(0xFF0C1117),
    onSurface = Color(0xFFDDE3EA),
    surfaceVariant = Color(0xFF26313B),
    onSurfaceVariant = Color(0xFFBBC7D2),
    outline = Color(0xFF85919C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTwinShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun AppTwinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppTwinDarkColors,
        shapes = AppTwinShapes,
        content = content,
    )
}
