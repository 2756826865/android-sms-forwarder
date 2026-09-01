package org.fossify.messages.ui.compose.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 经典品牌极简绿调 (MIUI / 微信绿体系)
val ClassicGreen = Color(0xFF159447)
val ClassicFabGreen = Color(0xFF1DCE38)
val ClassicBadgeGreen = Color(0xFF20C45A)

// Modern High-Fidelity Design Tokens
val BrandGreen = Color(0xFF159447)
val BrandGreenDark = Color(0xFF087A36)
val BrandGreenSoft = Color(0xFFEAF8EE)
val AppBackground = Color(0xFFF7F9FC)
val SurfaceCard = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF111827)
val TextSecondary = Color(0xFF5F6673)
val TextTertiary = Color(0xFF8A919E)
val OutlineSoft = Color(0xFFE5E9EF)
val RefreshIconBlue = Color(0xFF3A7698)

// Gateway Industrial Colors
val GatewayGreen = Color(0xFF159447)
val GatewayBlue = Color(0xFF1565C0)
val GatewayOrange = Color(0xFFEF6C00)
val GatewayRed = Color(0xFFC62828)
val GatewayPurple = Color(0xFF6A1B9A)

// Dark Palette (还原原有深色体系)
val DarkPrimary = Color(0xFF81C784)
val DarkOnPrimary = Color(0xFF003915)
val DarkPrimaryContainer = Color(0xFF1B4D2E)
val DarkOnPrimaryContainer = Color(0xFFC8E6C9)
val DarkSecondary = Color(0xFF80CBC4)
val DarkBackground = Color(0xFF121417)
val DarkSurface = Color(0xFF1A1D21)
val DarkSurfaceVariant = Color(0xFF262A30)
val DarkOutline = Color(0xFF3F444D)

// Light Palette (底色与卡片完全还原回原有层次体系，主品牌色保留经典翡翠绿)
val LightPrimary = Color(0xFF159447)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE8F5E9)
val LightOnPrimaryContainer = Color(0xFF0A4F24)
val LightSecondary = Color(0xFF00796B)
val LightBackground = Color(0xFFF8F9FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDEFF3)
val LightOutline = Color(0xFFD0D4DC)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    outline = LightOutline
)

@Composable
fun GatewayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
