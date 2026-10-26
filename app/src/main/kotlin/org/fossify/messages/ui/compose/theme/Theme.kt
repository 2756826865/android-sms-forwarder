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

val LocalGatewayDark = androidx.compose.runtime.staticCompositionLocalOf { false }

// Modern High-Fidelity Design Tokens
val BrandGreen
    @Composable get() = MaterialTheme.colorScheme.primary
val BrandGreenDark
    @Composable get() = MaterialTheme.colorScheme.primary
val BrandGreenSoft
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
val AppBackground
    @Composable get() = MaterialTheme.colorScheme.background
val SurfaceCard
    @Composable get() = MaterialTheme.colorScheme.surface
val TextPrimary
    @Composable get() = MaterialTheme.colorScheme.onSurface
val TextSecondary
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val TextTertiary
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val OutlineSoft
    @Composable get() = MaterialTheme.colorScheme.outlineVariant
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember(context) { context.getSharedPreferences("gateway_appearance", 0) }
    val state = androidx.compose.runtime.remember(prefs) {
        androidx.compose.runtime.mutableStateOf(Appearance(prefs.getInt("mode", 0), prefs.getBoolean("dynamic", false),
            prefs.getInt("accent", 0), prefs.getBoolean("amoled", false), prefs.getBoolean("blur", false),
            prefs.getInt("key_color", if (prefs.contains("accent")) 0 else 0xFF1F7169.toInt()),
            prefs.getString("palette", "TonalSpot") ?: "TonalSpot", prefs.getBoolean("spec2025", true),
            prefs.getBoolean("floating", true), prefs.getBoolean("material", false)))
    }
    val settings = state.value
    val dark = when (settings.mode) { 1 -> false; 2 -> true; else -> darkTheme }
    val accent = if (settings.keyColor != 0) Color(settings.keyColor) else when (settings.accent) { 1 -> Color(0xFF2962C0); 2 -> Color(0xFF8050A0); else -> LightPrimary }
    val base = if (settings.dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
    } else {
        (if (dark) DarkColorScheme else LightColorScheme).copy(
            primary = if (dark) androidx.compose.ui.graphics.lerp(accent, Color.White, 0.45f) else accent,
            primaryContainer = androidx.compose.ui.graphics.lerp(accent, if (dark) Color.Black else Color.White, 0.85f)
        )
    }
    val colorScheme = if (dark && settings.amoled) base.copy(background = Color.Black, surface = Color.Black) else base

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppearance provides settings,
        LocalGatewayDark provides dark,
        LocalSetAppearance provides { next ->
            state.value = next
            prefs.edit().putInt("mode", next.mode).putBoolean("dynamic", next.dynamic)
                .putInt("accent", next.accent).putBoolean("amoled", next.amoled).putBoolean("blur", next.blur).putInt("key_color", next.keyColor)
                .putString("palette", next.palette).putBoolean("spec2025", next.spec2025).putBoolean("floating", next.floating).putBoolean("material", next.material).apply()
        }
    ) {
        top.yukonga.miuix.kmp.theme.MiuixTheme(
            controller = top.yukonga.miuix.kmp.theme.ThemeController(
                if (dark) top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetDark else top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetLight,
                keyColor = if (settings.dynamic && settings.keyColor == 0) colorScheme.primary else if (settings.keyColor != 0) Color(settings.keyColor) else accent,
                isDark = dark,
                paletteStyle = runCatching { top.yukonga.miuix.kmp.theme.ThemePaletteStyle.valueOf(settings.palette) }
                    .getOrDefault(top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot),
                colorSpec = if (settings.spec2025) top.yukonga.miuix.kmp.theme.ThemeColorSpec.Spec2025
                    else top.yukonga.miuix.kmp.theme.ThemeColorSpec.Spec2021
            )
        ) {
            val generated = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
            val neutralPage = if (dark) { if (settings.amoled) Color.Black else Color(0xFF121417) } else Color(0xFFF5F5F5)
            val neutralCard = if (dark) Color(0xFF1E2024) else Color.White
            val neutralText = if (dark) Color(0xFFE6E6E6) else Color(0xFF202124)
            val miuix = generated.copy(
                primary = if (settings.dynamic) generated.primary else accent,
                surface = neutralPage,
                background = neutralPage,
                surfaceContainer = neutralCard,
                surfaceVariant = neutralCard,
                onBackground = neutralText,
                onSurface = neutralText,
                onSurfaceContainer = neutralText
            )
            top.yukonga.miuix.kmp.theme.MiuixTheme(colors = miuix) {
            MaterialTheme(colorScheme = colorScheme.copy(
                primary = miuix.primary,
                background = if (dark && settings.amoled) Color.Black else miuix.background,
                surface = neutralCard,
                surfaceBright = neutralCard,
                surfaceContainer = neutralPage,
                onBackground = miuix.onBackground,
                onSurface = miuix.onSurface,
                onSurfaceVariant = if (dark) Color(0xFFBCC1C8) else Color(0xFF5F6673)
            )) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides neutralText,
                    content = content
                )
            }
            }
        }
    }
}
