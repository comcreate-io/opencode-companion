package dev.local.opencodecompanion.connected

import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.CompanionTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Local configuration overrides only; networking and storage use the private HTTPS fixture. */
@OptIn(ExperimentalTestApi::class)
@SdkSuppress(minSdkVersion = 30)
class AccessibilityLayoutTest {
    val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(compose)
            .around(
                object : TestWatcher() {
                    override fun failed(error: Throwable, description: Description) {
                        // Preserve the assertion if screenshot capture itself is unavailable.
                        runCatching { screenshot("failed-${description.methodName}.png") }
                    }
                }
            )

    private val args
        get() = InstrumentationRegistry.getArguments()

    @Test fun largeTextLightCoreFlowAndKeyboard() = exercise(dark = false)

    @Test fun largeTextDarkCoreFlowAndKeyboard() = exercise(dark = true)

    private fun exercise(dark: Boolean) {
        val variant = if (dark) "dark" else "light"
        lateinit var viewModel: ConnectedViewModel
        compose.runOnUiThread {
            viewModel =
                ViewModelProvider(
                    compose.activity,
                    ConnectedViewModel.Factory(compose.activity.application),
                )[ConnectedViewModel::class.java]
            viewModel.foreground()
        }
        // The Activity's ViewModelStore owns cleanup. No singleton or default app database.
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(dark)) {
                    CompanionTheme { ConnectedScreen(viewModel) }
                }
            }
        }
        compose.onNodeWithTag("machine-list").assertExists()
        val name = "Large text $variant fixture"
        compose
            .onNodeWithTag("machine-list")
            .performScrollToNode(hasContentDescription("Machine name"))
        field("Machine name").performScrollTo().performTextInput(name)
        field("https://host.example")
            .performScrollTo()
            .performTextInput(requireNotNull(args.getString("fixtureOrigin")))
        field("Server password")
            .performScrollTo()
            .performTextInput(requireNotNull(args.getString("fixturePassword")))
        closeKeyboard()
        compose
            .onNodeWithText(" I understand and accept")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        val save = compose.onNodeWithText("Save machine").performScrollTo()
        assertButton(save)
        save.assertIsEnabled().performClick()
        awaitText("Ready · host current")
        compose
            .onNodeWithTag("machine-list")
            .performScrollToNode(hasText(name) and hasClickAction() and hasSetTextAction().not())
        compose
            .onNode(hasText(name) and hasClickAction() and hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        awaitText("New session")
        awaitText("Ready · host current")
        val newSession = compose.onNodeWithText("New session").performScrollTo()
        assertButton(newSession)
        newSession.assertIsEnabled().performClick()
        compose.onNodeWithTag("session-catalog").performScrollToNode(hasText("Fixture"))
        compose.onNodeWithText("Fixture").performClick().assertIsSelected()
        compose.onNodeWithTag("session-catalog").performScrollToNode(hasText("Create session"))
        val create = compose.onNodeWithText("Create session")
        assertButton(create)
        create.assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("Ready · host current")

        val draft = (1..20).joinToString("\n") { "Synthetic draft line $it" }
        field("Message").performClick().performTextInput(draft)
        showKeyboard()
        awaitSendEnabled()
        field("Message").assertIsFocused().assertTextEquals(draft)
        assertFullyVisible(field("Message"), aboveIme = true)
        assertButton(compose.onNodeWithText("Send"), aboveIme = true)
        screenshot("large-text-$variant-draft-ime.png")

        // Replace locally; the long draft is never sent. Trigger only the known synthetic tool.
        field("Message").performTextReplacement("QUESTION_CASE")
        awaitSendEnabled()
        compose.onNodeWithText("Send").performClick()
        awaitText("Continue Android fixture?")
        field("Message").performClick().performTextInput("Synthetic pending-question draft")
        showKeyboard()
        compose
            .onNodeWithText("Yes")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        val answer = compose.onNodeWithText("Answer").performScrollTo()
        assertButton(answer, aboveIme = true)
        answer.assertIsEnabled()
        assertFullyVisible(field("Message"), aboveIme = true)
        assertButton(compose.onNodeWithText("Send"), aboveIme = true)
        screenshot("large-text-$variant-question-ime.png")
        answer.performClick()
        awaitText("Fixture complete.")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Continue Android fixture?").fetchSemanticsNodes().isEmpty()
        }
        field("Message").assertTextEquals("Synthetic pending-question draft")
    }

    private fun field(label: String) = compose.onNodeWithContentDescription(label)

    private fun awaitText(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitSendEnabled() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertButton(node: SemanticsNodeInteraction, aboveIme: Boolean = false) {
        node
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Action width must be at least 48dp", bounds.right - bounds.left >= 48.dp)
        assertTrue("Action height must be at least 48dp", bounds.bottom - bounds.top >= 48.dp)
        assertFullyVisible(node, aboveIme)
    }

    private fun assertFullyVisible(node: SemanticsNodeInteraction, aboveIme: Boolean) {
        node.assertIsDisplayed()
        val unclipped = node.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "Control must fit horizontally",
            unclipped.left >= root.left && unclipped.right <= root.right,
        )
        assertTrue(
            "Control must fit vertically",
            unclipped.top >= root.top && unclipped.bottom <= root.bottom,
        )
        val visible = node.fetchSemanticsNode().boundsInWindow
        val density = compose.density.density
        assertTrue(
            "Control must not be horizontally clipped",
            visible.width + 1f >= (unclipped.right - unclipped.left).value * density,
        )
        assertTrue(
            "Control must not be vertically clipped",
            visible.height + 1f >= (unclipped.bottom - unclipped.top).value * density,
        )
        if (aboveIme) {
            var bottom = 0
            var visibleIme = false
            compose.runOnUiThread {
                val insets = compose.activity.window.decorView.rootWindowInsets
                visibleIme = insets?.isVisible(WindowInsets.Type.ime()) == true
                bottom =
                    compose.activity.windowManager.currentWindowMetrics.bounds.height() -
                        (insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0)
            }
            assertTrue("Keyboard must actually be visible", visibleIme)
            assertTrue("Control must be above keyboard", visible.bottom <= bottom + 1f)
        }
    }

    private fun showKeyboard() {
        compose.runOnUiThread {
            compose.activity.currentFocus?.let { focused ->
                compose.activity
                    .getSystemService(InputMethodManager::class.java)
                    .showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnUiThread {
                visible =
                    compose.activity.window.decorView.rootWindowInsets?.isVisible(
                        WindowInsets.Type.ime()
                    ) == true
            }
            visible
        }
        compose.waitForIdle()
    }

    private fun closeKeyboard() {
        compose.runOnUiThread {
            compose.activity
                .getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(10_000) {
            var visible = true
            compose.runOnUiThread {
                visible =
                    compose.activity.window.decorView.rootWindowInsets?.isVisible(
                        WindowInsets.Type.ime()
                    ) == true
            }
            !visible
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
        try {
            val directory =
                requireNotNull(
                    compose.activity.getExternalFilesDir(
                        requireNotNull(args.getString("fixtureEvidenceDirectory"))
                    )
                )
            File(directory, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
