package com.childfocus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary        = Color(0xFF4FC3F7),
    onPrimary      = Color(0xFF0D1B2A),
    background     = Color(0xFF0D1B2A),
    onBackground   = Color.White,
    surface        = Color(0xFF1E3A5F),
    onSurface      = Color.White,
)

@Composable
fun ChildFocusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
