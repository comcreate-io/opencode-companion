package dev.local.opencodecompanion.connected

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.opencodecompanion.CompanionColors
import dev.local.opencodecompanion.CompanionTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Normal-size foreground text must remain readable on its actual semantic surface. */
class ThemeContrastTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun smallTextMeetsContrastInBothThemes() {
        val dark = mutableStateOf(false)
        lateinit var colors: CompanionColors
        compose.setContent { CompanionTheme(dark = dark.value) { colors = CompanionTheme.colors } }
        for (mode in listOf(false, true)) {
            compose.runOnUiThread { dark.value = mode }
            compose.waitForIdle()
            val pairs =
                listOf(
                    Triple("input hint", colors.faint, colors.layer2),
                    Triple("connection ready", colors.success, colors.layer1),
                    Triple("patch truncation", colors.warning, colors.background),
                    Triple("active action", colors.accentText, colors.layer1),
                    Triple("primary action", colors.onAccent, colors.accent),
                    Triple("body", colors.text, colors.background),
                    Triple("secondary", colors.muted, colors.layer1),
                )
            for ((label, foreground, background) in pairs) {
                val ratio = contrast(foreground, background)
                assertTrue(
                    "$label in dark=$mode has contrast $ratio; expected >= 4.5",
                    ratio >= 4.5f,
                )
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
}
