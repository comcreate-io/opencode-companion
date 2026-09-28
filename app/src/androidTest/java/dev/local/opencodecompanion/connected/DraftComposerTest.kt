package dev.local.opencodecompanion.connected

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.local.opencodecompanion.CompanionTheme
import dev.local.opencodecompanion.client.storage.DraftKey
import dev.local.opencodecompanion.client.storage.DraftSnapshot
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.ProjectId
import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DraftComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val session = SessionKey(MachineId("machine"), SessionId("session"))
    private val key =
        DraftKey(session, ProjectKey(session.machineId, ProjectId("project")), "/test")

    private fun draft(revision: Long, text: String, cleared: Boolean = false) =
        DraftSnapshot(key, revision, text, cleared)

    @Test
    fun delayedSaveAndSendClearDoNotReplaceNewerInput() {
        var persisted by mutableStateOf<DraftSnapshot?>(null)
        val saveRequests = mutableListOf<Pair<String, () -> Unit>>()
        var sends = 0
        compose.setContent {
            CompanionTheme {
                DraftComposer(
                    session = session,
                    draft = persisted,
                    ready = true,
                    busy = false,
                    active = false,
                    interrupting = false,
                    onSave = { text, completed -> saveRequests += text to completed },
                    onSend = { sends++ },
                    onInterrupt = {},
                )
            }
        }

        val input = compose.onNodeWithContentDescription("Message")
        input.performTextInput("M")
        input.performTextInput("INI_UNSENT_DRAFT")
        compose.runOnIdle {
            persisted = draft(1, "M")
            saveRequests[0].second()
        }
        input.assertTextEquals("MINI_UNSENT_DRAFT")
        compose.onNodeWithText("Send").assertIsNotEnabled()
        assertEquals(listOf("M", "MINI_UNSENT_DRAFT"), saveRequests.map { it.first })

        compose.runOnIdle {
            persisted = draft(2, "MINI_UNSENT_DRAFT")
            saveRequests[1].second()
        }
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        assertEquals(1, sends)
        input.performTextReplacement("")
        input.performTextInput("MINI_UNSENT_DRAFT")
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.runOnIdle { persisted = draft(3, "", cleared = true) }
        input.assertTextEquals("MINI_UNSENT_DRAFT")

        compose.runOnIdle {
            persisted = draft(4, "")
            saveRequests[2].second()
        }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.runOnIdle {
            persisted = draft(5, "MINI_UNSENT_DRAFT")
            saveRequests[3].second()
        }
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        compose.runOnIdle { persisted = draft(6, "", cleared = true) }
        input.assertTextEquals("")
        assertEquals(2, sends)
    }
}
