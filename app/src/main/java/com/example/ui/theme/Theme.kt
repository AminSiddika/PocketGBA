package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val PocketDarkColorScheme = darkColorScheme(
    primary = HandheldIndigo,
    onPrimary = TextPrimary,
    primaryContainer = IndigoMuted,
    onPrimaryContainer = TextPrimary,
    secondary = LuminousViolet,
    onSecondary = GraphiteBg,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = AmberBoost,
    onTertiary = GraphiteBg,
    error = CrimsonAction,
    onError = TextPrimary,
    background = GraphiteBg,
    onBackground = TextPrimary,
    surface = AnodizedSlate,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle
)

private val PocketLightColorScheme = lightColorScheme(
    primary = HandheldIndigo,
    onPrimary = LightSurface,
    primaryContainer = LuminousViolet.copy(alpha = 0.18f),
    onPrimaryContainer = HandheldIndigo,
    secondary = IndigoMuted,
    onSecondary = LightSurface,
    tertiary = AmberBoost,
    onTertiary = LightTextPrimary,
    error = CrimsonAction,
    onError = LightSurface,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightTextSecondary.copy(alpha = 0.25f)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PocketAdvanceTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PocketDarkColorScheme else PocketLightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
