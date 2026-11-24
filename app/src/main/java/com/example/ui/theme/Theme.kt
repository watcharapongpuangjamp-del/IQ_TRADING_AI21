package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class TradingSemanticColors(
    val bullish: Color = ElectricEmerald,
    val bullishContainer: Color = EmeraldContainer,
    val bearish: Color = SharpCrimson,
    val bearishContainer: Color = CrimsonContainer,
    val cyan: Color = SignalCyan,
    val cyanContainer: Color = CyanContainer,
    val warning: Color = AmberGold,
    val warningContainer: Color = AmberContainer,
    val cardBorder: Color = SubtleBorder,
    val surfaceElevated: Color = DarkGraphite,
    val surfaceCanvas: Color = DeepObsidian
)

val LocalTradingColors = staticCompositionLocalOf { TradingSemanticColors() }

private val DarkTerminalColorScheme = darkColorScheme(
    primary = SignalCyan,
    onPrimary = DeepObsidian,
    primaryContainer = CyanContainer,
    onPrimaryContainer = SignalCyan,
    secondary = ElectricEmerald,
    onSecondary = DeepObsidian,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = ElectricEmerald,
    tertiary = AmberGold,
    onTertiary = DeepObsidian,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberGold,
    error = SharpCrimson,
    onError = IceWhite,
    errorContainer = CrimsonContainer,
    onErrorContainer = SharpCrimson,
    background = DeepObsidian,
    onBackground = IceWhite,
    surface = TerminalSlate,
    onSurface = IceWhite,
    surfaceVariant = DarkGraphite,
    onSurfaceVariant = CoolSteel,
    surfaceContainerLowest = DeepObsidian,
    surfaceContainerLow = TerminalSlate,
    surfaceContainer = DarkGraphite,
    surfaceContainerHigh = ElevatedGraphite,
    outline = SubtleBorder,
    outlineVariant = MutedSlate
)

private val TerminalShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun IQTradingTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalTradingColors provides TradingSemanticColors()) {
        MaterialTheme(
            colorScheme = DarkTerminalColorScheme,
            typography = Typography,
            shapes = TerminalShapes,
            content = content
        )
    }
}
