package com.example.mempal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Orange,
    primaryContainer = AppColors.Orange,
    secondary = AppColors.Orange,
    background = AppColors.NavyBlue,
    surface = AppColors.DarkGray,
    surfaceVariant = AppColors.DarkerNavy,
    error = Color(0xFFCF6679),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = AppColors.DataGray,
    onSurfaceVariant = Color.White,
    onError = Color.Black
)

@Composable
fun MempalTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}