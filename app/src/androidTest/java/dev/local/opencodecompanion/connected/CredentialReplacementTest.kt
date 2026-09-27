package dev.local.opencodecompanion.connected

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.MainActivity
import org.junit.Rule
import org.junit.Test

/** Uses only the runner's disposable HTTPS host and seeded session; never sends a prompt. */
class CredentialReplacementTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args
        get() = InstrumentationRegistry.getArguments()

    private val machineName = "Credential replacement host"
    private val draftText = "Draft remains after credential replacement"

    @Test
    fun rejectedReplacementCanBeCorrectedWithoutLosingDraft() {
        val sessionId = requireNotNull(args.getString("fixtureSession"))
        require(sessionId.startsWith("ses_"))
        val fixturePassword = requireNotNull(args.getString("fixturePassword"))
        val wrongPassword = "intentionally-wrong-disposable-password"
        require(wrongPassword != fixturePassword)

        compose.onNodeWithTag("machine-list").assertExists()
        compose.onNodeWithContentDescription("Machine name").performTextInput(machineName)
        compose
            .onNodeWithContentDescription("https://host.example")
            .performTextInput(requireNotNull(args.getString("fixtureOrigin")))
        compose.onNodeWithContentDescription("Server password").performTextInput(fixturePassword)
        closeSoftKeyboard()
        compose.onNodeWithText(" I understand and accept").performScrollTo().performClick()
        compose.onNodeWithText("Save machine").performScrollTo().performClick()
        selectMachine()
        openSession(sessionId)
        compose.onNodeWithContentDescription("Message").performTextInput(draftText)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Message").assertTextEquals(draftText)
        closeSoftKeyboard()

        openMachines()
        updatePassword(wrongPassword)
        awaitText("The host rejected this credential. Update its saved password from Machines.")
        compose.onNodeWithText("Ready · host current").assertDoesNotExist()

        updatePassword(fixturePassword)
        awaitText("Ready · host current")
        selectMachine()
        openSession(sessionId)
        compose.waitUntil(30_000) {
            try {
                compose.onNodeWithContentDescription("Message").assertTextEquals(draftText)
                true
            } catch (_: AssertionError) {
                false
            }
        }
        compose.onNodeWithContentDescription("Message").assertTextEquals(draftText)
        compose.onNodeWithText("Ready · host current").assertExists()
    }

    private fun openMachines() {
        compose.onNodeWithText("Machines").performClick()
        compose.onNodeWithTag("machine-list").assertExists()
    }

    private fun updatePassword(secret: String) {
        compose.onNodeWithText("Update password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Updated username").assertExists()
        compose.onNodeWithContentDescription("Updated server password").performTextInput(secret)
        closeSoftKeyboard()
        compose.onNodeWithText("Save updated password").performScrollTo().performClick()
    }

    private fun selectMachine() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("machine-list").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(machineName).fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNode(hasText(machineName) and hasClickAction() and hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("session-list").assertExists()
        awaitText("Ready · host current")
    }

    private fun openSession(sessionId: String) {
        compose.onNodeWithTag("session-list").performScrollToNode(hasTestTag("session:$sessionId"))
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("session:$sessionId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session:$sessionId").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Message").fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("Ready · host current")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertExists()
    }

    private fun closeSoftKeyboard() {
        compose.runOnUiThread {
            compose.activity
                .getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
    }
}
