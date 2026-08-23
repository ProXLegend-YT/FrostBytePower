package com.frostbyte.power.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FrostBg = Color(0xFF0B0E14)
private val FrostSurface = Color(0xFF151A24)
private val FrostAccent = Color(0xFF4FD1FF)
private val FrostTextPrimary = Color(0xFFF2F6FA)
private val FrostTextSecondary = Color(0xFF8B99AC)
private val FrostDanger = Color(0xFFFF5C6C)

private val FrostColorScheme = darkColorScheme(
    primary = FrostAccent,
    onPrimary = FrostBg,
    background = FrostBg,
    onBackground = FrostTextPrimary,
    surface = FrostSurface,
    onSurface = FrostTextPrimary,
    surfaceVariant = FrostSurface,
    onSurfaceVariant = FrostTextSecondary,
    error = FrostDanger
)

@Composable
fun FrostBytePowerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FrostColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
