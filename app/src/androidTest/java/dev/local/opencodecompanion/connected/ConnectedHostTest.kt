package dev.local.opencodecompanion.connected

import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.MainActivity
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Actual native UI -> Room/Keystore -> strict HTTPS -> pinned disposable OpenCode. */
class ConnectedHostTest {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule
    val rules: RuleChain =
        RuleChain.outerRule(compose)
            .around(
                object : TestWatcher() {
                    override fun failed(error: Throwable, description: Description) {
                        screenshot("failed-" + description.methodName + ".png")
                    }
                }
            )
    private val args
        get() = InstrumentationRegistry.getArguments()

    private fun closeSoftKeyboard() {
        compose.runOnUiThread {
            compose.activity
                .getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
    }

    private fun awaitText(text: String, timeout: Long = 30_000, substring: Boolean = false) {
        compose.waitUntil(timeout) {
            compose
                .onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun setup(name: String) {
        compose.onNodeWithContentDescription("Machine name").performTextInput(name)
        compose
            .onNodeWithContentDescription("https://host.example")
            .performTextInput(requireNotNull(args.getString("fixtureOrigin")))
        compose
            .onNodeWithContentDescription("Server password")
            .performTextInput(requireNotNull(args.getString("fixturePassword")))
        closeSoftKeyboard()
        compose.onNodeWithText(" I understand and accept").performScrollTo().performClick()
        compose.onNodeWithText("Save machine").performScrollTo().performClick()
        awaitText("Ready · host current")
        compose
            .onNode(hasText(name) and hasClickAction() and hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        awaitText("New session")
    }

    private fun newSession() {
        awaitText("Ready · host current")
        compose.onNodeWithText("New session").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("session-catalog").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session-catalog").performScrollToNode(hasText("Fixture"))
        compose.onNodeWithText("Fixture").performClick()
        compose.onNodeWithTag("session-catalog").performScrollToNode(hasText("Create session"))
        compose.onNodeWithText("Create session").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("Ready · host current")
    }

    private fun send(text: String) {
        compose.onNodeWithContentDescription("Message").performTextInput(text)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send").performClick()
        closeSoftKeyboard()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val screenshot =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
        val directory =
            requireNotNull(
                compose.activity.getExternalFilesDir(
                    requireNotNull(args.getString("fixtureEvidenceDirectory"))
                )
            )
        File(directory, name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test
    fun readToolDraftRecreationAndChanges() {
        setup("Android read fixture")
        newSession()
        send("READ_CASE")
        awaitText("Fixture complete.")
        awaitText("ANDROID_READ_MARKER", substring = true)
        compose
            .onNodeWithContentDescription("Message")
            .performTextInput("Draft survives recreation")
        closeSoftKeyboard()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNodeWithContentDescription("Message")
            .assertTextContains("Draft survives recreation")
        awaitText("Ready · host current")
        screenshot("connected-read-draft.png")
        compose.onNodeWithText("Changes").performClick()
        awaitText("No changes reported")
        screenshot("connected-changes.png")
        compose.onNodeWithText("Conversation").performClick()
        compose
            .onNodeWithContentDescription("Message")
            .assertTextContains("Draft survives recreation")
    }

    @Test
    fun questionReplyUsesRealPendingRequest() {
        setup("Android question fixture")
        newSession()
        send("QUESTION_CASE")
        awaitText("Continue Android fixture?")
        screenshot("connected-question.png")
        compose.onNodeWithText("Yes", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Answer").performScrollTo().performClick()
        awaitText("Fixture complete.")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Continue Android fixture?").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Continue Android fixture?").assertDoesNotExist()
        screenshot("connected-question-answered.png")
    }

    @Test
    fun interruptRemainsSeparateFromDisconnect() {
        setup("Android interrupt fixture")
        newSession()
        send("INTERRUPT_CASE")
        awaitText("Stop")
        compose.onNodeWithText("Stop").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Stopping").fetchSemanticsNodes().isEmpty()
        }
        awaitText("Ready · host current")
        screenshot("connected-interrupted.png")
    }
}
