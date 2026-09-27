package dev.local.opencodecompanion.connected

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.MainActivity
import org.junit.Rule
import org.junit.Test

/** Parent runner executes these methods in separate processes without clearing app data. */
class ProcessRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args
        get() = InstrumentationRegistry.getArguments()

    private val machineName = "Recovery host"
    private val draftText = "Persisted across process death"

    @Test
    fun prepareDurableDraft() {
        val session = requiredSession()
        compose.onNodeWithTag("machine-list").assertExists()
        compose.onNodeWithContentDescription("Machine name").performTextInput(machineName)
        compose
            .onNodeWithContentDescription("https://host.example")
            .performTextInput(requireNotNull(args.getString("fixtureOrigin")))
        compose
            .onNodeWithContentDescription("Server password")
            .performTextInput(requireNotNull(args.getString("fixturePassword")))
        closeSoftKeyboard()
        compose.onNodeWithText(" I understand and accept").performScrollTo().performClick()
        compose.onNodeWithText("Save machine").performScrollTo().performClick()
        selectExistingMachine()
        openSession(session)
        compose.onNodeWithContentDescription("Message").performTextInput(draftText)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Message").assertTextEquals(draftText)
        closeSoftKeyboard()
    }

    @Test
    fun restoreDurableDraft() {
        val session = requiredSession()
        selectExistingMachine()
        openSession(session)
        compose.waitUntil(30_000) {
            try {
                compose.onNodeWithContentDescription("Message").assertTextEquals(draftText)
                true
            } catch (_: AssertionError) {
                false
            }
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Ready · host current").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ready · host current").assertExists()
    }

    private fun requiredSession(): String =
        requireNotNull(args.getString("fixtureSession")).also { require(it.startsWith("ses_")) }

    private fun selectExistingMachine() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("machine-list").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(machineName).fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNode(hasText(machineName) and hasClickAction() and hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("session-list").assertExists()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Ready · host current").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(machineName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSession(session: String) {
        compose.onNodeWithTag("session-list").performScrollToNode(hasTestTag("session:$session"))
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("session:$session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session:$session").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose
                .onAllNodesWithContentDescription("Message")
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose
                    .onAllNodesWithText(machineName, substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Ready · host current").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ready · host current").assertExists()
    }

    private fun closeSoftKeyboard() {
        compose.runOnUiThread {
            compose.activity
                .getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
    }
}
