package dev.local.opencodecompanion.protocol.transcript

import dev.local.opencodecompanion.protocol.SessionKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class PromptSummary(val text: String, val delivery: String, val prompted: Boolean)

sealed interface StepStatus {
    data object Running : StepStatus

    data class Ended(val finish: String, val cost: Double, val tokens: TokenTotals) : StepStatus

    data class Failed(val error: TranscriptError) : StepStatus
}

data class StepSummary(
    val agent: String,
    val providerId: String,
    val modelId: String,
    val variant: String?,
    val status: StepStatus,
)

sealed interface TextSummary {
    data object Started : TextSummary

    data class Ended(val text: String) : TextSummary
}

sealed interface ToolSummary {
    val name: String

    data class InputStarted(override val name: String) : ToolSummary

    data class InputEnded(override val name: String, val raw: String) : ToolSummary

    data class Called(
        override val name: String,
        val raw: String,
        val input: JsonObject,
        val providerExecuted: Boolean,
    ) : ToolSummary

    data class Succeeded(
        override val name: String,
        val raw: String,
        val input: JsonObject,
        val structured: JsonObject,
        val content: JsonArray,
        val outputPaths: JsonArray?,
        val providerExecuted: Boolean,
    ) : ToolSummary

    data class Failed(
        override val name: String,
        val raw: String,
        val input: JsonObject,
        val error: TranscriptError,
        val providerExecuted: Boolean,
    ) : ToolSummary
}

data class PartKey(val messageId: String, val partId: String)

/** Rebuilds one Session from sequence zero. A caller persists this state and cursor together. */
data class TranscriptState(
    val session: SessionKey,
    val lastSequence: Long = 0,
    val prompts: Map<String, PromptSummary> = emptyMap(),
    val steps: Map<String, StepSummary> = emptyMap(),
    val texts: Map<PartKey, TextSummary> = emptyMap(),
    val tools: Map<PartKey, ToolSummary> = emptyMap(),
    val seen: Map<Long, DurableTranscriptEvent> = emptyMap(),
    val retainedWireChars: Long = 0,
) {
    fun apply(event: DurableTranscriptEvent): TranscriptApply {
        if (event.session != session)
            return TranscriptApply.Conflict("event belongs to another machine or session")
        if (event.sequence <= lastSequence) {
            val prior =
                seen[event.sequence] ?: return TranscriptApply.LateUnverified(event.sequence)
            return if (prior.sameContentAs(event)) {
                TranscriptApply.Duplicate
            } else TranscriptApply.Conflict("conflicting event at sequence ${event.sequence}")
        }
        if (event.sequence != lastSequence + 1)
            return TranscriptApply.Gap(lastSequence + 1, event.sequence)
        if (seen.size >= MAX_EVENTS || retainedWireChars + event.wireChars > MAX_WIRE_CHARS) {
            return TranscriptApply.Conflict("transcript state exceeds retained event budget")
        }
        val next =
            try {
                transition(event.kind)
            } catch (error: InvalidTransition) {
                return TranscriptApply.Conflict(error.message ?: "invalid transcript transition")
            }
        return TranscriptApply.Applied(
            next.copy(
                lastSequence = event.sequence,
                seen = seen + (event.sequence to event),
                retainedWireChars = retainedWireChars + event.wireChars,
            )
        )
    }

    private fun transition(kind: Kind): TranscriptState =
        when (kind) {
            is Kind.Prompt -> {
                val old = prompts[kind.messageId]
                if (
                    old != null &&
                        (old.text != kind.text ||
                            old.delivery != kind.delivery ||
                            old.prompted ||
                            !kind.prompted)
                )
                    invalid("conflicting prompt lifecycle")
                if (kind.prompted && old == null) invalid("prompted without admission")
                copy(
                    prompts =
                        prompts +
                            (kind.messageId to
                                PromptSummary(kind.text, kind.delivery, kind.prompted))
                )
            }
            is Kind.StepStarted -> {
                if (steps.containsKey(kind.messageId)) invalid("step already started")
                copy(
                    steps =
                        steps +
                            (kind.messageId to
                                StepSummary(
                                    kind.agent,
                                    kind.providerId,
                                    kind.modelId,
                                    kind.variant,
                                    StepStatus.Running,
                                ))
                )
            }
            is Kind.StepEnded -> {
                val old = runningStep(kind.messageId)
                copy(
                    steps =
                        steps +
                            (kind.messageId to
                                old.copy(
                                    status = StepStatus.Ended(kind.finish, kind.cost, kind.tokens)
                                ))
                )
            }
            is Kind.StepFailed -> {
                val old = runningStep(kind.messageId)
                copy(
                    steps =
                        steps + (kind.messageId to old.copy(status = StepStatus.Failed(kind.error)))
                )
            }
            is Kind.TextStarted -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.textId)
                if (texts.containsKey(key)) invalid("text already started")
                copy(texts = texts + (key to TextSummary.Started))
            }
            is Kind.TextEnded -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.textId)
                if (texts[key] != TextSummary.Started) invalid("text ended without start")
                copy(texts = texts + (key to TextSummary.Ended(kind.text)))
            }
            is Kind.ToolInputStarted -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.callId)
                if (tools.containsKey(key)) invalid("tool already started")
                copy(tools = tools + (key to ToolSummary.InputStarted(kind.name)))
            }
            is Kind.ToolInputEnded -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.callId)
                val old =
                    tools[key] as? ToolSummary.InputStarted
                        ?: invalid("tool input ended without start")
                copy(tools = tools + (key to ToolSummary.InputEnded(old.name, kind.text)))
            }
            is Kind.ToolCalled -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.callId)
                val old =
                    tools[key] as? ToolSummary.InputEnded
                        ?: invalid("tool called without completed input")
                if (old.name != kind.tool) invalid("tool name changed during call")
                copy(
                    tools =
                        tools +
                            (key to
                                ToolSummary.Called(
                                    old.name,
                                    old.raw,
                                    kind.input,
                                    kind.providerExecuted,
                                ))
                )
            }
            is Kind.ToolSuccess -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.callId)
                val old =
                    tools[key] as? ToolSummary.Called ?: invalid("tool succeeded without call")
                copy(
                    tools =
                        tools +
                            (key to
                                ToolSummary.Succeeded(
                                    old.name,
                                    old.raw,
                                    old.input,
                                    kind.structured,
                                    kind.content,
                                    kind.outputPaths,
                                    kind.providerExecuted,
                                ))
                )
            }
            is Kind.ToolFailed -> {
                runningStep(kind.messageId)
                val key = PartKey(kind.messageId, kind.callId)
                val old = tools[key] as? ToolSummary.Called ?: invalid("tool failed without call")
                copy(
                    tools =
                        tools +
                            (key to
                                ToolSummary.Failed(
                                    old.name,
                                    old.raw,
                                    old.input,
                                    kind.error,
                                    kind.providerExecuted,
                                ))
                )
            }
        }

    private fun runningStep(messageId: String): StepSummary =
        steps[messageId]?.takeIf { it.status == StepStatus.Running }
            ?: invalid("step is missing or settled")

    private fun invalid(message: String): Nothing = throw InvalidTransition(message)

    private companion object {
        const val MAX_EVENTS = 4096
        const val MAX_WIRE_CHARS = 4L * 1_048_576
    }
}

private class InvalidTransition(message: String) : IllegalStateException(message)

sealed interface TranscriptApply {
    data class Applied(val state: TranscriptState) : TranscriptApply

    data object Duplicate : TranscriptApply

    data class Gap(val expected: Long, val observed: Long) : TranscriptApply

    data class LateUnverified(val sequence: Long) : TranscriptApply

    data class Conflict(val reason: String) : TranscriptApply
}
