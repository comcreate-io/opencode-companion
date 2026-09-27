package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The 1.18.32 pending-request subset. The caller supplies the machine; transport rechecks stale
 * requests.
 */
object V2RequestCodec {
    private const val MAX_CHARS = 1_048_576
    private const val MAX_NESTING = 64

    fun permission(
        body: String,
        destination: SessionKey,
        expectedRequestId: String,
    ): V2PermissionRequest {
        val request = root(body).requiredObject("data").permissionRequest(destination)
        if (request.id != expectedRequestId)
            invalid("permission request ID does not match destination")
        return request
    }

    fun permissions(body: String, destination: SessionKey): List<V2PermissionRequest> =
        root(body).requiredArray("data").map {
            it.asObject("permission").permissionRequest(destination)
        }

    fun questions(body: String, destination: SessionKey): List<V2QuestionRequest> =
        root(body).requiredArray("data").map {
            it.asObject("question").questionRequest(destination)
        }

    fun permissionReply(
        request: V2PermissionRequest,
        reply: V2PermissionReply,
        message: String? = null,
    ): V2RequestPayload {
        val body =
            buildMap<String, JsonElement> {
                put("reply", JsonPrimitive(reply.wireValue))
                if (message != null) put("message", JsonPrimitive(message))
            }
        return V2RequestPayload(request.sessionKey, request.id, JsonObject(body).toString())
    }

    fun questionReply(request: V2QuestionRequest, answers: List<List<String>>): V2RequestPayload {
        if (answers.size != request.questions.size) invalid("answer count does not match questions")
        val body =
            JsonObject(
                mapOf(
                    "answers" to
                        JsonArray(answers.map { row -> JsonArray(row.map(::JsonPrimitive)) })
                )
            )
        return V2RequestPayload(request.sessionKey, request.id, body.toString())
    }

    /** The reject route has no request body. */
    fun questionReject(request: V2QuestionRequest): V2RequestPayload =
        V2RequestPayload(request.sessionKey, request.id, null)

    private fun JsonObject.permissionRequest(destination: SessionKey): V2PermissionRequest {
        val session = requiredId("sessionID")
        if (session != destination.sessionId.value)
            invalid("permission sessionID does not match destination")
        val source =
            optionalObject("source")?.let {
                if (it.requiredString("type") != "tool")
                    invalid("permission source type is unsupported")
                V2RequestSource(it.requiredId("messageID"), it.requiredId("callID"))
            }
        return V2PermissionRequest(
            id = requiredPrefixedId("id", "per"),
            sessionKey = destination,
            action = requiredString("action"),
            resources = requiredArray("resources").map { it.asString("resource") },
            save = optionalArray("save")?.map { it.asString("save item") },
            metadata = optionalObject("metadata"),
            source = source,
        )
    }

    private fun JsonObject.questionRequest(destination: SessionKey): V2QuestionRequest {
        val session = requiredId("sessionID")
        if (session != destination.sessionId.value)
            invalid("question sessionID does not match destination")
        val questions =
            requiredArray("questions").map { item ->
                val info = item.asObject("question info")
                V2QuestionInfo(
                    question = info.requiredString("question"),
                    header = info.requiredString("header"),
                    options =
                        info.requiredArray("options").map { option ->
                            val value = option.asObject("option")
                            V2QuestionOption(
                                value.requiredString("label"),
                                value.requiredString("description"),
                            )
                        },
                    multiple = info.optionalBoolean("multiple"),
                    custom = info.optionalBoolean("custom"),
                )
            }
        val tool =
            optionalObject("tool")?.let {
                V2RequestSource(it.requiredId("messageID"), it.requiredId("callID"))
            }
        return V2QuestionRequest(requiredPrefixedId("id", "que"), destination, questions, tool)
    }

    private fun root(body: String): JsonObject {
        if (body.length > MAX_CHARS) invalid("JSON exceeds size limit")
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
        val element =
            try {
                Json.parseToJsonElement(body)
            } catch (_: IllegalArgumentException) {
                invalid("invalid JSON")
            }
        return element.asObject("root")
    }

    private fun JsonElement.asObject(field: String): JsonObject =
        this as? JsonObject ?: invalid("$field must be an object")

    private fun JsonElement.asString(field: String): String {
        val value = this as? JsonPrimitive ?: invalid("$field must be a string")
        if (!value.isString) invalid("$field must be a string")
        return value.content
    }

    private fun JsonObject.requiredObject(field: String): JsonObject =
        get(field)?.asObject(field) ?: invalid("$field is required")

    private fun JsonObject.optionalObject(field: String): JsonObject? =
        when (val value = get(field)) {
            null -> null
            is JsonObject -> value
            else -> invalid("$field must be an object")
        }

    private fun JsonObject.requiredArray(field: String): JsonArray =
        get(field) as? JsonArray ?: invalid("$field must be an array")

    private fun JsonObject.optionalArray(field: String): JsonArray? =
        when (val value = get(field)) {
            null -> null
            is JsonArray -> value
            else -> invalid("$field must be an array")
        }

    private fun JsonObject.requiredString(field: String): String =
        get(field)?.asString(field) ?: invalid("$field is required")

    private fun JsonObject.requiredId(field: String): String =
        requiredString(field).also { if (it.isBlank()) invalid("$field must be nonblank") }

    private fun JsonObject.requiredPrefixedId(field: String, prefix: String): String =
        requiredId(field).also {
            if (!it.startsWith(prefix)) invalid("$field has unsupported prefix")
        }

    private fun JsonObject.optionalBoolean(field: String): Boolean? =
        when (val value = get(field)) {
            null -> null
            is JsonPrimitive ->
                if (!value.isString) value.booleanOrNull ?: invalid("$field must be a boolean")
                else invalid("$field must be a boolean")
            else -> invalid("$field must be a boolean")
        }

    private fun invalid(reason: String): Nothing = throw V2WireException(reason)
}

data class V2RequestSource(val messageId: String, val callId: String)

data class V2PermissionRequest(
    val id: String,
    val sessionKey: SessionKey,
    val action: String,
    val resources: List<String>,
    val save: List<String>?,
    val metadata: JsonObject?,
    val source: V2RequestSource?,
)

data class V2QuestionOption(val label: String, val description: String)

data class V2QuestionInfo(
    val question: String,
    val header: String,
    val options: List<V2QuestionOption>,
    val multiple: Boolean?,
    val custom: Boolean?,
)

data class V2QuestionRequest(
    val id: String,
    val sessionKey: SessionKey,
    val questions: List<V2QuestionInfo>,
    val tool: V2RequestSource?,
)

data class V2RequestPayload(val sessionKey: SessionKey, val requestId: String, val body: String?)

enum class V2PermissionReply(val wireValue: String) {
    ONCE("once"),
    REJECT("reject"),
}
