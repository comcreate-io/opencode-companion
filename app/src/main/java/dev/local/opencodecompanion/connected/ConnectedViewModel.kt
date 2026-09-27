package dev.local.opencodecompanion.connected

import android.app.Application
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.local.opencodecompanion.client.session.SessionActionResult
import dev.local.opencodecompanion.client.session.SessionCoordinator
import dev.local.opencodecompanion.client.session.SessionProblem
import dev.local.opencodecompanion.protocol.MachineId
import dev.local.opencodecompanion.protocol.SessionKey
import dev.local.opencodecompanion.protocol.V2CreateSessionCommand
import dev.local.opencodecompanion.protocol.V2PermissionReply
import dev.local.opencodecompanion.protocol.V2PermissionRequest
import dev.local.opencodecompanion.protocol.V2QuestionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The retained owner for one connected graph and its foreground streams. */
class ConnectedViewModel(create: (CoroutineScope) -> SessionCoordinator) : ViewModel() {
    private val coordinator = create(viewModelScope)
    val state = coordinator.state
    private val message = MutableStateFlow<String?>(null)
    val notice = message.asStateFlow()

    init {
        act { coordinator.initialize() }
    }

    fun foreground() {
        act { coordinator.foreground() }
    }

    fun background() {
        coordinator.background()
    }

    fun clearNotice() {
        message.value = null
    }

    fun addMachine(
        name: String,
        origin: String,
        username: String,
        password: String,
        accepted: Boolean,
    ) {
        if (
            username.isBlank() ||
                ':' in username ||
                username.any { it.code !in 0x20..0x7e } ||
                password.isBlank()
        ) {
            message.value = "Enter a valid username and server password."
            return
        }
        val authorization =
            "Basic " +
                Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
        act { coordinator.addMachine(name, origin, authorization, accepted) }
    }

    fun selectMachine(id: MachineId) {
        act { coordinator.selectMachine(id) }
    }

    fun replaceCredential(id: MachineId, username: String, password: String) {
        if (
            username.isBlank() ||
                ':' in username ||
                username.any { it.code !in 0x20..0x7e } ||
                password.isBlank()
        ) {
            message.value = "Enter a valid username and server password."
            return
        }
        val authorization =
            "Basic " +
                Base64.encodeToString(
                    "$username:$password".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP,
                )
        act { coordinator.replaceCredential(id, authorization) }
    }

    fun selectSession(key: SessionKey) {
        act { coordinator.selectSession(key) }
    }

    fun createSession(command: V2CreateSessionCommand) {
        act { coordinator.createSession(command) }
    }

    fun refreshChanges() {
        act { coordinator.refreshChanges() }
    }

    fun saveDraft(text: String) {
        act { coordinator.saveDraft(text) }
    }

    fun send() {
        act { coordinator.send() }
    }

    fun interrupt() {
        act { coordinator.interrupt() }
    }

    fun recheckPendingActions() {
        act { coordinator.recheckPendingActions() }
    }

    fun answerPermission(request: V2PermissionRequest, reply: V2PermissionReply) {
        act { coordinator.replyPermission(request, reply) }
    }

    fun answerQuestion(request: V2QuestionRequest, answers: List<List<String>>) {
        act { coordinator.replyQuestion(request, answers) }
    }

    fun rejectQuestion(request: V2QuestionRequest) {
        act { coordinator.rejectQuestion(request) }
    }

    private fun act(block: suspend () -> SessionActionResult) {
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    SessionActionResult.Completed -> message.value = null
                    is SessionActionResult.Stopped -> message.value = result.problem.explanation()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = "The local operation could not complete. Try again."
            }
        }
    }

    override fun onCleared() {
        coordinator.close()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConnectedViewModel::class.java))
            // The factory receives the ViewModel scope only after the ViewModel exists; the
            // coordinator owns a child scope and is closed by onCleared.
            @Suppress("UNCHECKED_CAST")
            return ConnectedViewModel { scope ->
                (application as ConnectedGraphFactory).createCoordinator(application, scope)
            }
                as T
        }
    }
}

internal fun SessionProblem.explanation(): String =
    when (this) {
        SessionProblem.RotationBlocked ->
            "The password was not replaced. Resolve any uncertain sends on this machine before updating access."
        SessionProblem.RotationRecoveryRequired ->
            "Saved access needs recovery. Close and reopen the app to check it again. Sending stays blocked if recovery fails."
        SessionProblem.MutationInProgress -> "An action is already in progress on this host."
        SessionProblem.RequestStale ->
            "This request changed or was already answered. No response was sent."
        SessionProblem.SetupRequired -> "Choose a machine and session before continuing."
        SessionProblem.CredentialUnavailable ->
            "The saved credential is unavailable. Check this machine's access settings."
        SessionProblem.RemoteUseNotApproved ->
            "Accept the shared-password access terms before connecting."
        SessionProblem.StorageFailure -> "Local state could not be saved. Nothing was sent again."
        SessionProblem.ProtocolUnsupported -> "This host returned an unsupported session event."
        SessionProblem.OutcomeUnknown ->
            "The host may have received this action. Check its result before trying again."
        SessionProblem.DestinationChanged ->
            "The machine or session changed. Review the current context."
        is SessionProblem.Transport ->
            when (reason) {
                dev.local.opencodecompanion.client.ReadFailure.AuthenticationRequired ->
                    "The host rejected this credential. Update its saved password from Machines."
                dev.local.opencodecompanion.client.ReadFailure.TlsRejected ->
                    "The host certificate could not be verified. Check its HTTPS configuration."
                dev.local.opencodecompanion.client.ReadFailure.RedirectRejected ->
                    "The host redirected this request. Use its direct HTTPS address."
                dev.local.opencodecompanion.client.ReadFailure.TooLarge ->
                    "The host response exceeds this candidate's size limit."
                dev.local.opencodecompanion.client.ReadFailure.DecodeFailure ->
                    "The host response does not match the supported protocol."
                dev.local.opencodecompanion.client.ReadFailure.UnsupportedVersion ->
                    "This candidate requires OpenCode 1.18.32."
                dev.local.opencodecompanion.client.ReadFailure.TransportUnavailable ->
                    "The host is unreachable. Check its connection and try again."
                is dev.local.opencodecompanion.client.ReadFailure.HttpStatus ->
                    "The host could not complete this request. Try reconnecting."
            }
    }
