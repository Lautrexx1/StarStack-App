package com.starstack.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// AMOLED Dark Color Scheme - true black for night sky photography
private val StarStackDarkColorScheme = darkColorScheme(
    primary              = Color(0xFF1E88E5),
    onPrimary            = Color(0xFFFFFFFF),
    primaryContainer     = Color(0xFF0D47A1),
    onPrimaryContainer   = Color(0xFFBBDEFB),
    secondary            = Color(0xFF546E7A),
    onSecondary          = Color(0xFFFFFFFF),
    secondaryContainer   = Color(0xFF263238),
    onSecondaryContainer = Color(0xFFB0BEC5),
    tertiary             = Color(0xFF2E7D32),
    onTertiary           = Color(0xFFFFFFFF),
    tertiaryContainer    = Color(0xFF1B5E20),
    onTertiaryContainer  = Color(0xFFA5D6A7),
    error                = Color(0xFFD32F2F),
    onError              = Color(0xFFFFFFFF),
    errorContainer       = Color(0xFF7F0000),
    onErrorContainer     = Color(0xFFFFCDD2),
    background           = Color(0xFF000000),
    onBackground         = Color(0xFFE0E0E0),
    surface              = Color(0xFF0A0A0A),
    onSurface            = Color(0xFFE0E0E0),
    surfaceVariant       = Color(0xFF141414),
    onSurfaceVariant     = Color(0xFF9E9E9E),
    outline              = Color(0xFF2A2A2A),
    outlineVariant       = Color(0xFF1A1A1A),
    scrim                = Color(0xFF000000),
    inverseSurface       = Color(0xFFE0E0E0),
    inverseOnSurface     = Color(0xFF000000),
    inversePrimary       = Color(0xFF1565C0)
)

@Composable
fun StarStackTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StarStackDarkColorScheme,
        content = content
    )
}
