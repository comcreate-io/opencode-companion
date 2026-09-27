package dev.local.opencodecompanion.connected

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.MainActivity
import org.junit.Rule
import org.junit.Test

/** Replies once to an actual request seeded by the disposable HTTPS fixture. */
class PermissionReplyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val args
        get() = InstrumentationRegistry.getArguments()

    private val machineName = "Permission fixture host"
    private val permissionTitle = "Permission · android.fixture.permission"

    @Test
    fun allowOnceClearsRealPendingRequestAndKeepsConnectionReady() {
        val sessionId = requireNotNull(args.getString("fixtureSession"))
        require(sessionId.startsWith("ses_"))
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

        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(machineName).fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNode(hasText(machineName) and hasClickAction() and hasSetTextAction().not())
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("session-list").assertExists()
        awaitText("Ready · host current")
        compose.onNodeWithTag("session-list").performScrollToNode(hasTestTag("session:$sessionId"))
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("session:$sessionId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("session:$sessionId").performScrollTo().performClick()
        awaitText("Ready · host current")
        awaitText(permissionTitle)
        compose.onNodeWithText("fixture://android").assertExists()
        compose.onNodeWithText("Allow once").performScrollTo().performClick()

        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(permissionTitle).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Allow once").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(permissionTitle).assertDoesNotExist()
        compose.onNodeWithText("Allow once").assertDoesNotExist()
        compose.onNodeWithText("Ready · host current").assertExists()
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
