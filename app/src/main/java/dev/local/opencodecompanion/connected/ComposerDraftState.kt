package dev.local.opencodecompanion.connected

import dev.local.opencodecompanion.client.storage.DraftSnapshot
import dev.local.opencodecompanion.protocol.SessionKey

/** Local input wins over delayed persistence acknowledgements for its selected session. */
internal data class ComposerDraftState(
    val text: String = "",
    private val edited: Boolean = false,
    val generation: Long = 0,
    private val submittedGeneration: Long? = null,
    private val observedRevision: Long? = null,
    private val savedGeneration: Long = 0,
) {
    fun edit(value: String): ComposerDraftState =
        copy(text = value, edited = true, generation = generation + 1)

    fun submit(): ComposerDraftState = copy(submittedGeneration = generation)

    fun saved(editGeneration: Long): ComposerDraftState =
        if (editGeneration == generation) copy(savedGeneration = editGeneration) else this

    fun canSend(session: SessionKey?, draft: DraftSnapshot?, ready: Boolean, busy: Boolean) =
        ready &&
            !busy &&
            text.isNotBlank() &&
            savedGeneration == generation &&
            draft?.takeIf { it.key.session == session && !it.cleared }?.text == text

    fun observe(session: SessionKey?, draft: DraftSnapshot?): ComposerDraftState {
        if (session == null || draft == null || draft.key.session != session) return this
        if (observedRevision != null && draft.revision <= observedRevision) return this

        // A matching tombstone is the acknowledgement for the submitted input. If the user
        // typed after Send, that newer input remains theirs even while its save is pending.
        val clearSubmitted = draft.cleared && submittedGeneration == generation
        val replacement =
            when {
                clearSubmitted -> ""
                !edited -> if (draft.cleared) "" else draft.text
                else -> text
            }
        return copy(
            text = replacement,
            edited = if (clearSubmitted) false else edited,
            submittedGeneration = if (draft.cleared) null else submittedGeneration,
            observedRevision = draft.revision,
        )
    }
}
