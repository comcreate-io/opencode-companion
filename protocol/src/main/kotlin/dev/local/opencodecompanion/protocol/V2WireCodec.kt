package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Decodes only the verified OpenCode 1.18.32 response subset. No app state is inferred here. */
object V2WireCodec {
    private const val MAX_INPUT_CHARS = 1_048_576
    private const val MAX_NESTING = 64
    private const val ADMITTED_TYPE = "session.next.prompt.admitted"
    private val json = Json

    fun health(body: String) {
        if (!root(body).requiredBoolean("healthy")) invalid("host is not healthy")
    }

    fun session(body: String): V2SessionSummary = root(body).requiredObject("data").sessionSummary()

    fun sessions(body: String): V2SessionPage {
        val root = root(body)
        val sessions = root.requiredArray("data").map { it.asObject("data item").sessionSummary() }
        val cursor = root.requiredObject("cursor")
        return V2SessionPage(
            sessions = sessions,
            previous = cursor.optionalNonblankString("previous"),
            next = cursor.optionalNonblankString("next"),
        )
    }

    fun admission(
        body: String,
        expectedSession: SessionId,
        expectedMessageId: String,
    ): V2PromptAdmission {
        if (expectedMessageId.isBlank()) invalid("expected message ID is blank")
        val admitted = root(body).requiredObject("data")
        val sessionId = admitted.requiredNonblankString("sessionID")
        if (sessionId != expectedSession.value)
            invalid("admission sessionID does not match destination")
        val messageId = admitted.requiredNonblankString("id")
        if (messageId != expectedMessageId) invalid("admission message ID does not match intent")
        return V2PromptAdmission(
            id = messageId,
            sessionId = expectedSession,
            admittedSequence = admitted.requiredPositiveLong("admittedSeq"),
            delivery = admitted.requiredDelivery("delivery"),
            promptText = admitted.requiredObject("prompt").requiredString("text"),
            timeCreated = admitted.requiredLong("timeCreated"),
        )
    }

    fun promptConflict(body: String, expectedMessageId: String): V2PromptConflict {
        if (expectedMessageId.isBlank()) invalid("expected message ID is blank")
        val conflict = root(body)
        if (conflict.requiredString("_tag") != "ConflictError") invalid("not a prompt conflict")
        val resource = conflict.requiredNonblankString("resource")
        if (resource != expectedMessageId) invalid("conflict resource does not match message")
        conflict.requiredString("message")
        return V2PromptConflict(resource)
    }

    /**
     * Unknown durable semantics invalidate the page; the caller must not persist a cursor from it.
     */
    fun history(body: String, expectedSession: SessionId, after: Long): V2HistoryDecode {
        if (after < 0) invalid("after cursor must be nonnegative")
        val root = root(body)
        val hasMore = root.requiredBoolean("hasMore")
        var prior = after
        val events = mutableListOf<V2AdmittedEvent>()
        for (item in root.requiredArray("data")) {
            val event = item.asObject("event")
            val durable = event.requiredObject("durable")
            val sequence = durable.requiredPositiveLong("seq")
            if (sequence <= prior) invalid("event sequence must increase after cursor")
            prior = sequence
            if (durable.requiredNonblankString("aggregateID") != expectedSession.value) {
                invalid("event aggregateID does not match destination")
            }
            val version = durable.requiredPositiveLong("version")
            val type = event.requiredNonblankString("type")
            if (type != ADMITTED_TYPE || version != 1L) {
                return V2HistoryDecode.Unsupported(
                    sequence = sequence,
                    type = type,
                    version = version,
                )
            }
            val data = event.requiredObject("data")
            if (data.requiredNonblankString("sessionID") != expectedSession.value) {
                invalid("event data.sessionID does not match destination")
            }
            events +=
                V2AdmittedEvent(
                    id = event.requiredNonblankString("id"),
                    sequence = sequence,
                    sessionId = expectedSession,
                    messageId = data.requiredNonblankString("messageID"),
                    timestamp = data.requiredLong("timestamp"),
                    promptText = data.requiredObject("prompt").requiredString("text"),
                    delivery = data.requiredDelivery("delivery"),
                )
        }
        return V2HistoryDecode.Supported(V2HistoryPage(events, hasMore))
    }

    /** A session SSE frame carries the same durable event shape as one history page entry. */
    fun durableEvent(body: String, expectedSession: SessionId, after: Long): V2HistoryDecode {
        val event = root(body)
        val page =
            JsonObject(mapOf("data" to JsonArray(listOf(event)), "hasMore" to JsonPrimitive(false)))
        return history(page.toString(), expectedSession, after)
    }

    private fun root(body: String): JsonObject {
        validateBounds(body)
        val element =
            try {
                json.parseToJsonElement(body)
            } catch (_: IllegalArgumentException) {
                invalid("invalid JSON")
            }
        return element.asObject("root")
    }

    private fun validateBounds(body: String) {
        if (body.length > MAX_INPUT_CHARS) invalid("JSON exceeds size limit")
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
                    '[' -> if (++depth > MAX_NESTING) invalid("JSON exceeds nesting limit")
                    '}',
                    ']' -> depth--
                }
            }
        }
    }

    private fun JsonObject.sessionSummary(): V2SessionSummary {
        val time = requiredObject("time")
        return V2SessionSummary(
            id = SessionId(requiredNonblankString("id")),
            projectId = ProjectId(requiredNonblankString("projectID")),
            directory = requiredObject("location").requiredNonblankString("directory"),
            workspaceId = requiredObject("location").optionalNonblankString("workspaceID"),
            subpath = optionalString("subpath"),
            title = requiredString("title"),
            created = time.requiredLong("created"),
            updated = time.requiredLong("updated"),
        )
    }

    private fun JsonElement.asObject(field: String): JsonObject =
        this as? JsonObject ?: invalid("$field must be an object")

    private fun JsonObject.requiredObject(field: String): JsonObject =
        get(field)?.asObject(field) ?: invalid("$field is required")

    private fun JsonObject.requiredArray(field: String): JsonArray =
        get(field) as? JsonArray ?: invalid("$field must be an array")

    private fun JsonObject.requiredString(field: String): String {
        val primitive = get(field) as? JsonPrimitive ?: invalid("$field must be a string")
        if (!primitive.isString) invalid("$field must be a string")
        return primitive.contentOrNull ?: invalid("$field must be a string")
    }

    private fun JsonObject.requiredNonblankString(field: String): String =
        requiredString(field).also { if (it.isBlank()) invalid("$field must be nonblank") }

    private fun JsonObject.requiredDelivery(field: String): String =
        requiredString(field).also {
            if (it != "steer" && it != "queue") invalid("$field is unsupported")
        }

    private fun JsonObject.optionalString(field: String): String? =
        when (val value = get(field)) {
            null,
            JsonNull -> null
            is JsonPrimitive ->
                if (value.isString) value.contentOrNull
                else invalid("$field must be a string or null")
            else -> invalid("$field must be a string or null")
        }

    private fun JsonObject.optionalNonblankString(field: String): String? =
        optionalString(field)?.also { if (it.isBlank()) invalid("$field must be nonblank") }

    private fun JsonObject.requiredLong(field: String): Long {
        val value = get(field) as? JsonPrimitive ?: invalid("$field must be an integer")
        if (value.isString || value.content.any { it == '.' || it == 'e' || it == 'E' })
            invalid("$field must be an integer")
        return value.longOrNull ?: invalid("$field must be an integer")
    }

    private fun JsonObject.requiredPositiveLong(field: String): Long =
        requiredLong(field).also { if (it <= 0) invalid("$field must be positive") }

    private fun JsonObject.requiredBoolean(field: String): Boolean {
        val value = get(field) as? JsonPrimitive ?: invalid("$field must be a boolean")
        if (value.isString) invalid("$field must be a boolean")
        return value.booleanOrNull ?: invalid("$field must be a boolean")
    }

    private fun invalid(reason: String): Nothing = throw V2WireException(reason)
}

class V2WireException(message: String) : IllegalArgumentException(message)

data class V2SessionSummary(
    val id: SessionId,
    val projectId: ProjectId,
    val directory: String,
    val workspaceId: String?,
    val subpath: String?,
    val title: String,
    val created: Long,
    val updated: Long,
)

data class V2SessionPage(
    val sessions: List<V2SessionSummary>,
    val previous: String?,
    val next: String?,
)

data class V2PromptAdmission(
    val id: String,
    val sessionId: SessionId,
    val admittedSequence: Long,
    val delivery: String,
    val promptText: String,
    val timeCreated: Long,
)

data class V2PromptConflict(val messageId: String)

data class V2AdmittedEvent(
    val id: String,
    val sequence: Long,
    val sessionId: SessionId,
    val messageId: String,
    val timestamp: Long,
    val promptText: String,
    val delivery: String,
)

data class V2HistoryPage(val events: List<V2AdmittedEvent>, val hasMore: Boolean)

sealed interface V2HistoryDecode {
    data class Supported(val page: V2HistoryPage) : V2HistoryDecode

    data class Unsupported(val sequence: Long, val type: String, val version: Long) :
        V2HistoryDecode
}
