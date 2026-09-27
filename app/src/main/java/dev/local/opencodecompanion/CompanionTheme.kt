package dev.local.opencodecompanion

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Resolved semantic colors from OpenCode V2's pinned explicit theme blocks. Source:
 * references/opencode/packages/ui/src/v2/styles/{colors,theme}.css at b471c2b. Inter is
 * intentionally not bundled until its distribution license is reviewed; Android sans is used.
 */
data class CompanionColors(
    val background: Color,
    val deep: Color,
    val layer1: Color,
    val layer2: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val warningBackground: Color,
    val danger: Color,
    val dangerBackground: Color,
)

private val LightColors =
    CompanionColors(
        background = Color(0xFFFFFFFF),
        deep = Color(0xFFFAFAFA),
        layer1 = Color(0xFFFAFAFA),
        layer2 = Color(0xFFF2F2F2),
        text = Color(0xFF161616),
        muted = Color(0xFF5C5C5C),
        faint = Color(0xFF808080),
        border = Color(0x14000000),
        accent = Color(0xFF3B5CF6),
        accentText = Color(0xFF3B5CF6),
        onAccent = Color.White,
        success = Color(0xFF198B43),
        warning = Color(0xFFCB9F34),
        warningBackground = Color(0xFFFEFAEC),
        danger = Color(0xFFB82D35),
        dangerBackground = Color(0xFFFCECEB),
    )

private val DarkColors =
    CompanionColors(
        background = Color(0xFF161616),
        deep = Color(0xFF080808),
        layer1 = Color(0xFF242424),
        layer2 = Color(0xFF2E2E2E),
        text = Color(0xFFFAFAFA),
        muted = Color(0xFFAEAEAE),
        faint = Color(0xFF808080),
        border = Color(0x14FFFFFF),
        accent = Color(0xFF3B5CF6),
        accentText = Color(0xFFA2BCFF),
        onAccent = Color.White,
        success = Color(0xFF6BD586),
        warning = Color(0xFFF2CF76),
        warningBackground = Color(0xFF4B4025),
        danger = Color(0xFFF17471),
        dangerBackground = Color(0xFF461516),
    )

private val LocalCompanionColors = staticCompositionLocalOf { LightColors }

object CompanionTheme {
    val colors: CompanionColors
        @Composable get() = LocalCompanionColors.current
}

@Composable
fun CompanionTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCompanionColors provides if (dark) DarkColors else LightColors,
        content = content,
    )
}
