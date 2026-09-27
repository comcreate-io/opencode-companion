package dev.local.opencodecompanion.client

import dev.local.opencodecompanion.protocol.ProjectKey
import dev.local.opencodecompanion.protocol.SessionKey

/**
 * A command uses this captured destination throughout its lifetime, including after UI host
 * switches.
 */
data class SendDestination(
    val session: SessionKey,
    val project: ProjectKey,
    val location: String,
    val credentialGeneration: Long,
) {
    init {
        require(session.machineId == project.machineId) {
            "Session and project must belong to the same machine"
        }
        require(location.isNotBlank())
        require(credentialGeneration >= 0)
    }
}

sealed interface SendState {
    data object Prepared : SendState

    data object Dispatching : SendState

    data object OutcomeUnknown : SendState

    data object Admitted : SendState

    data object Rejected : SendState

    data object Finalized : SendState
}

/**
 * Pure state rules for a send intent. The caller must durably write Prepared before beginDispatch,
 * and durably write Dispatching before network I/O. Storage and host reconciliation are not here.
 */
class OutgoingIntent
private constructor(val id: String, val destination: SendDestination, val state: SendState) {
    init {
        require(id.isNotBlank())
    }

    companion object {
        fun prepare(id: String, destination: SendDestination): OutgoingIntent =
            OutgoingIntent(id, destination, SendState.Prepared)
    }

    fun beginDispatch(): OutgoingIntent = transition(SendState.Prepared, SendState.Dispatching)

    fun responseMissing(): OutgoingIntent =
        transition(SendState.Dispatching, SendState.OutcomeUnknown)

    /** A process that died during dispatch has no proof the host did not accept the command. */
    fun recoverAfterProcessDeath(): OutgoingIntent =
        if (state == SendState.Dispatching) withState(SendState.OutcomeUnknown) else this

    /** Call only after a response or correlated authoritative host evidence proves admission. */
    fun recordAdmission(): OutgoingIntent = resolve(SendState.Admitted)

    /** Call only after an explicit response or authoritative evidence proves rejection. */
    fun recordRejection(): OutgoingIntent = resolve(SendState.Rejected)

    /** Local bookkeeping/retention only. Retrying this transition must never dispatch a prompt. */
    fun finalizeLocalBookkeeping(): OutgoingIntent {
        if (state == SendState.Finalized) return this
        check(state == SendState.Admitted || state == SendState.Rejected) {
            "Only a resolved intent can be finalized"
        }
        return withState(SendState.Finalized)
    }

    private fun resolve(resolved: SendState): OutgoingIntent {
        check(state == SendState.Dispatching || state == SendState.OutcomeUnknown) {
            "Only an outstanding dispatch can be resolved"
        }
        return withState(resolved)
    }

    private fun transition(from: SendState, to: SendState): OutgoingIntent {
        check(state == from) { "Cannot transition from $state to $to" }
        return withState(to)
    }

    private fun withState(next: SendState) = OutgoingIntent(id, destination, next)

    override fun equals(other: Any?): Boolean =
        other is OutgoingIntent &&
            id == other.id &&
            destination == other.destination &&
            state == other.state

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + destination.hashCode()) + state.hashCode()
}
