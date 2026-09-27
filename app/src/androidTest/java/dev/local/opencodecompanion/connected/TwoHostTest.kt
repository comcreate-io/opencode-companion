package dev.local.opencodecompanion.connected

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.MainActivity
import org.junit.Rule
import org.junit.Test

/** Two real disposable HTTPS hosts own the same upstream session ID independently. */
class TwoHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args
        get() = InstrumentationRegistry.getArguments()

    @Test
    fun identicalSessionIdsKeepDraftsOnTheirOwnMachines() {
        val sharedSession = requireNotNull(args.getString("fixtureSession"))
        require(sharedSession.startsWith("ses_"))
        setup("Host A", "fixtureOrigin", "fixturePassword")
        openSession("Host A", sharedSession)
        writeAndPersist("Draft A stays on host A")

        openMachines()
        setup("Host B", "fixtureOrigin2", "fixturePassword2")
        openSession("Host B", sharedSession)
        awaitComposerEmpty()
        writeAndPersist("Draft B stays on host B")

        openMachines()
        selectMachine("Host A")
        openSession("Host A", sharedSession)
        awaitComposer("Draft A stays on host A")

        openMachines()
        selectMachine("Host B")
        openSession("Host B", sharedSession)
        awaitComposer("Draft B stays on host B")
    }

    private fun setup(name: String, originArg: String, passwordArg: String) {
        compose.onNodeWithTag("machine-list").assertExists()
        compose.onNodeWithContentDescription("Machine name").performTextInput(name)
        compose
            .onNodeWithContentDescription("https://host.example")
            .performTextInput(requireNotNull(args.getString(originArg)))
        compose
            .onNodeWithContentDescription("Server password")
            .performTextInput(requireNotNull(args.getString(passwordArg)))
        closeSoftKeyboard()
        compose.onNodeWithText(" I understand and accept").performScrollTo().performClick()
        compose.onNodeWithText("Save machine").performScrollTo().performClick()
        selectMachine(name)
    }

    private fun selectMachine(name: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("machine-list").fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNode(hasText(name) and hasClickAction() and hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("session-list").assertExists()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Ready · host current").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSession(machineName: String, sharedSession: String) {
        compose.onNodeWithTag("session-list").assertExists()
        compose.waitUntil(30_000) {
            compose
                .onAllNodesWithTag("session:$sharedSession")
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithText(machineName).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session:$sharedSession").performScrollTo().performClick()
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

    private fun writeAndPersist(text: String) {
        compose.onNodeWithContentDescription("Message").performTextInput(text)
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Send") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Message").assertTextContains(text)
        closeSoftKeyboard()
    }

    private fun awaitComposer(text: String) {
        compose.waitUntil(30_000) {
            try {
                compose.onNodeWithContentDescription("Message").assertTextEquals(text)
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun awaitComposerEmpty() {
        compose.waitUntil(30_000) {
            try {
                // Placeholder text is also merged into this node; assert the editable value.
                compose
                    .onNodeWithContentDescription("Message")
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.EditableText,
                            AnnotatedString(""),
                        )
                    )
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun openMachines() {
        closeSoftKeyboard()
        compose.onNodeWithText("Machines").performClick()
        compose.onNodeWithTag("machine-list").assertExists()
    }

    private fun closeSoftKeyboard() {
        compose.runOnUiThread {
            compose.activity
                .getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
    }
}
