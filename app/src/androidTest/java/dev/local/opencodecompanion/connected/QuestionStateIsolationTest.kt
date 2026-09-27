package dev.local.opencodecompanion.connected

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.opencodecompanion.CompanionTheme
import dev.local.opencodecompanion.client.session.ConnectionState
import dev.local.opencodecompanion.client.session.SessionUiState
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2QuestionInfo
import dev.local.opencodecompanion.protocol.V2QuestionOption
import dev.local.opencodecompanion.protocol.V2QuestionRequest
import org.junit.Rule
import org.junit.Test

/** Reuses one composition slot and request ID; never submits or rejects a request. */
class QuestionStateIsolationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun anotherMachineWithSameSessionAndRequestIdsDoesNotInheritAnswers() {
        verifyReset(
            SessionKey(MachineId("question-fixture-a"), SessionId("ses_fixture_shared")),
            SessionKey(MachineId("question-fixture-b"), SessionId("ses_fixture_shared")),
        )
    }

    @Test
    fun anotherSessionOnSameMachineDoesNotInheritAnswers() {
        verifyReset(
            SessionKey(MachineId("question-fixture-a"), SessionId("ses_fixture_first")),
            SessionKey(MachineId("question-fixture-a"), SessionId("ses_fixture_second")),
        )
    }

    private fun verifyReset(first: SessionKey, second: SessionKey) {
        val args = InstrumentationRegistry.getArguments()
        // Fail closed if invoked outside the private fixture runner.
        require(!args.getString("fixtureCertificate").isNullOrBlank())
        require(!args.getString("fixtureDatabase").isNullOrBlank())
        lateinit var viewModel: ConnectedViewModel
        compose.runOnUiThread {
            require(compose.activity.application is FixtureApplication)
            viewModel =
                ViewModelProvider(
                    compose.activity,
                    ConnectedViewModel.Factory(compose.activity.application),
                )[ConnectedViewModel::class.java]
        }
        val currentRequest = mutableStateOf(request(first))
        compose.setContent {
            CompanionTheme {
                // Deliberately no key(request.sessionKey): QuestionCard owns its local identity.
                QuestionCard(
                    currentRequest.value,
                    SessionUiState(
                        selectedMachine = currentRequest.value.sessionKey.machineId,
                        selectedSession = currentRequest.value.sessionKey,
                        connection = ConnectionState.Ready,
                    ),
                    viewModel,
                )
            }
        }
        val yes = compose.onNodeWithTag("question-option:que_fixture_shared:0:Yes")
        val custom = compose.onNodeWithContentDescription("Other answer")
        yes.assertIsNotSelected().performClick().assertIsSelected()
        custom.performTextInput("Synthetic answer for the original destination")
        custom.assertTextEquals("Synthetic answer for the original destination")
        compose.onNodeWithText("Answer").assertIsEnabled()

        compose.runOnIdle { currentRequest.value = currentRequest.value.copy(sessionKey = second) }
        // Same request ID and controls, different full SessionKey in the same composition slot.
        yes.assertIsNotSelected()
        custom.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.onNodeWithText("Answer").assertIsNotEnabled()
    }

    private fun request(key: SessionKey) =
        V2QuestionRequest(
            id = "que_fixture_shared",
            sessionKey = key,
            questions =
                listOf(
                    V2QuestionInfo(
                        question = "Synthetic choice?",
                        header = "Choice",
                        options = listOf(V2QuestionOption("Yes", "Synthetic affirmative option")),
                        multiple = false,
                        custom = false,
                    ),
                    V2QuestionInfo(
                        question = "Synthetic custom answer?",
                        header = "Custom",
                        options = listOf(V2QuestionOption("Alternative", "Synthetic fallback")),
                        multiple = false,
                        custom = true,
                    ),
                ),
            tool = null,
        )
}
