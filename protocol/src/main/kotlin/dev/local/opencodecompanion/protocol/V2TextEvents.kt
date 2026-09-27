package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Only the observed text slice of OpenCode 1.18.32. This decoder grants no replay cursor. */
object V2TextEvents {
    const val MAX_TEXT_CHARS = 1_048_576
    private const val MAX_EVENT_CHARS = MAX_TEXT_CHARS + 4096
    private const val MAX_NESTING = 64
    private const val MAX_ID_CHARS = 256

    fun decode(body: String, session: SessionKey): V2TextDecode {
        if (body.length > MAX_EVENT_CHARS) invalid("text event exceeds size limit")
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{',
                    '[' -> if (++depth > MAX_NESTING) invalid("text event exceeds nesting limit")
                    '}',
                    ']' -> depth--
                }
            }
        }
        val root =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: invalid("text event must be a JSON object")
        val type = root.string("type")
        if (type != "session.next.text.delta" && type != "session.next.text.ended") {
            return V2TextDecode.NotText(type)
        }
        val data = root.obj("data")
        if (data.string("sessionID") != session.sessionId.value) invalid("text session mismatch")
        val key =
            V2TextKey(
                session,
                data.string("assistantMessageID").also(::validId),
                data.string("textID").also(::validId),
            )
        val id = root.string("id").also(::validId)
        if (type == "session.next.text.delta") {
            if (root.containsKey("durable")) invalid("text delta must be transient")
            return V2TextDecode.Text(V2TextEvent.Delta(key, id, data.string("delta")))
        }
        val durable = root.obj("durable")
        if (durable.string("aggregateID") != session.sessionId.value)
            invalid("text aggregate mismatch")
        if (durable.positiveLong("version") != 1L) invalid("unsupported text end version")
        return V2TextDecode.Text(
            V2TextEvent.Ended(key, id, durable.positiveLong("seq"), data.string("text"))
        )
    }

    private fun JsonObject.obj(field: String): JsonObject =
        this[field] as? JsonObject ?: invalid("$field must be an object")

    private fun JsonObject.string(field: String): String {
        val value = this[field] as? JsonPrimitive ?: invalid("$field must be a string")
        if (!value.isString) invalid("$field must be a string")
        return value.contentOrNull ?: invalid("$field must be a string")
    }

    private fun JsonObject.positiveLong(field: String): Long {
        val value = this[field] as? JsonPrimitive ?: invalid("$field must be a positive integer")
        if (value.isString || value.content.any { it == '.' || it == 'e' || it == 'E' }) {
            invalid("$field must be a positive integer")
        }
        return value.longOrNull?.takeIf { it > 0 } ?: invalid("$field must be a positive integer")
    }

    private fun validId(value: String) {
        if (value.isBlank() || value.length > MAX_ID_CHARS) invalid("text identity is invalid")
    }

    private fun invalid(message: String): Nothing = throw V2WireException(message)
}

data class V2TextKey(val session: SessionKey, val assistantMessageId: String, val textId: String)

sealed interface V2TextDecode {
    data class Text(val event: V2TextEvent) : V2TextDecode

    data class NotText(val type: String) : V2TextDecode
}

sealed interface V2TextEvent {
    val key: V2TextKey
    val id: String

    data class Delta(override val key: V2TextKey, override val id: String, val text: String) :
        V2TextEvent

    data class Ended(
        override val key: V2TextKey,
        override val id: String,
        val sequence: Long,
        val text: String,
    ) : V2TextEvent
}

sealed interface V2TextFragment {
    val text: String

    data class Provisional(override val text: String, val deltaIds: Set<String>) : V2TextFragment

    data class Final(override val text: String, val eventId: String, val sequence: Long) :
        V2TextFragment
}

/** Immutable, bounded projection. A durable end replaces any provisional stream text. */
data class V2TextProjection(val fragments: Map<V2TextKey, V2TextFragment> = emptyMap()) {
    fun apply(event: V2TextEvent): V2TextProjection {
        if (
            event.id.isBlank() ||
                event.id.length > MAX_ID_CHARS ||
                event.key.assistantMessageId.isBlank() ||
                event.key.assistantMessageId.length > MAX_ID_CHARS ||
                event.key.textId.isBlank() ||
                event.key.textId.length > MAX_ID_CHARS
        )
            throw V2WireException("text identity is invalid")
        val old = fragments[event.key]
        if (old == null && fragments.size >= MAX_FRAGMENTS) {
            throw V2WireException("text fragment count exceeds limit")
        }
        val next =
            when (event) {
                is V2TextEvent.Delta -> {
                    if (old is V2TextFragment.Final) return this
                    if (old is V2TextFragment.Provisional && event.id in old.deltaIds) return this
                    val prior = (old as? V2TextFragment.Provisional)?.text ?: ""
                    if (event.text.length > V2TextEvents.MAX_TEXT_CHARS - prior.length) {
                        throw V2WireException("text fragment exceeds size limit")
                    }
                    val retainedDeltaIds =
                        fragments.values.sumOf {
                            (it as? V2TextFragment.Provisional)?.deltaIds?.size ?: 0
                        }
                    if (retainedDeltaIds >= MAX_TOTAL_DELTA_IDS) {
                        throw V2WireException("text projection delta count exceeds limit")
                    }
                    val text = prior + event.text
                    val ids =
                        ((old as? V2TextFragment.Provisional)?.deltaIds ?: emptySet()) + event.id
                    if (ids.size > MAX_DELTA_IDS)
                        throw V2WireException("text delta count exceeds limit")
                    V2TextFragment.Provisional(text, ids)
                }
                is V2TextEvent.Ended -> {
                    if (event.text.length > V2TextEvents.MAX_TEXT_CHARS) {
                        throw V2WireException("text fragment exceeds size limit")
                    }
                    if (old is V2TextFragment.Final) {
                        if (
                            old.eventId == event.id &&
                                old.sequence == event.sequence &&
                                old.text == event.text
                        ) {
                            return this
                        }
                        throw V2WireException("conflicting final text event")
                    }
                    V2TextFragment.Final(event.text, event.id, event.sequence)
                }
            }
        val used =
            fragments.values.sumOf { it.text.length.toLong() } - (old?.text?.length ?: 0) +
                next.text.length
        if (used > MAX_TOTAL_CHARS) throw V2WireException("text projection exceeds size limit")
        return copy(fragments = fragments + (event.key to next))
    }

    private companion object {
        const val MAX_FRAGMENTS = 4096
        const val MAX_DELTA_IDS = 4096
        const val MAX_TOTAL_DELTA_IDS = 4096
        const val MAX_TOTAL_CHARS = 4L * 1_048_576
        const val MAX_ID_CHARS = 256
    }
}
