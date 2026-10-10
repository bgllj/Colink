package com.colink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF7F1E6)
val PaperLight = Color(0xFFFFFCF6)
val Ink = Color(0xFF1F322B)
val Green = Color(0xFF2F6F5E)
val GreenSoft = Color(0xFFDCECE2)
val Moss = Color(0xFF6D8D74)
val Sand = Color(0xFFF0E1BE)
val Line = Color(0xFFD9D2C5)

private val ColinkScheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenSoft,
    onPrimaryContainer = Ink,
    secondary = Moss,
    background = Paper,
    onBackground = Ink,
    surface = PaperLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0E9DD),
    outline = Color(0xFF817B70),
)

@Composable
fun ColinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColinkScheme, typography = Typography(), content = content)
}
