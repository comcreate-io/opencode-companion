package dev.local.opencodecompanion.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The source calls the second delivery mode `queue`; it is not a client-side retry policy. */
enum class V2Delivery(val wireValue: String) {
    Steer("steer"),
    Queue("queue"),
}

data class V2ModelSelection(val providerId: String, val modelId: String)

/** `directory` is required when `location` is supplied to session.create. */
data class V2LocationSelection(val directory: String, val workspaceId: String? = null)

data class V2CreateSessionCommand(
    val agentId: String? = null,
    val model: V2ModelSelection? = null,
    val location: V2LocationSelection? = null,
    val requestedSessionId: SessionId? = null,
)

data class V2PromptCommand(
    val sessionKey: SessionKey,
    val messageId: String,
    val text: String,
    val delivery: V2Delivery,
)

/** Wire payloads for the pinned V2 create and text-only prompt routes. */
object V2SessionCommands {
    fun create(command: V2CreateSessionCommand): String {
        val fields =
            buildMap<String, JsonElement> {
                command.requestedSessionId?.let {
                    put("id", JsonPrimitive(V2BoundedJson.id(it.value)))
                }
                command.agentId?.let { put("agent", JsonPrimitive(V2BoundedJson.id(it))) }
                command.model?.let {
                    put(
                        "model",
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(V2BoundedJson.id(it.modelId)),
                                "providerID" to JsonPrimitive(V2BoundedJson.id(it.providerId)),
                            )
                        ),
                    )
                }
                command.location?.let {
                    put(
                        "location",
                        JsonObject(
                            buildMap {
                                put("directory", JsonPrimitive(V2BoundedJson.path(it.directory)))
                                it.workspaceId?.let { workspace ->
                                    put("workspaceID", JsonPrimitive(V2BoundedJson.id(workspace)))
                                }
                            }
                        ),
                    )
                }
            }
        return V2BoundedJson.output(JsonObject(fields))
    }

    fun prompt(command: V2PromptCommand): String {
        V2BoundedJson.id(command.sessionKey.sessionId.value)
        if (command.text.length > V2BoundedJson.MAX_TEXT_CHARS)
            throw V2WireException("prompt text exceeds size limit")
        return V2BoundedJson.output(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(V2BoundedJson.id(command.messageId)),
                    "prompt" to JsonObject(mapOf("text" to JsonPrimitive(command.text))),
                    "delivery" to JsonPrimitive(command.delivery.wireValue),
                    "resume" to JsonPrimitive(true),
                )
            )
        )
    }

    /** Missing keys mean inactive in this host process, not a durable execution result. */
    fun active(body: String): Set<SessionId> {
        val data = with(V2BoundedJson) { root(body).obj("data") }
        if (data.size > 4096) throw V2WireException("active session count exceeds limit")
        return data.mapTo(LinkedHashSet()) { (id, status) ->
            if (with(V2BoundedJson) { (status as? JsonObject)?.string("type") } != "running")
                throw V2WireException("unsupported active status")
            SessionId(V2BoundedJson.id(id))
        }
    }
}

/** Small shared parser boundary for the new protocol codecs; never returns raw JSON to callers. */
internal object V2BoundedJson {
    const val MAX_TEXT_CHARS = 1_048_576
    private const val MAX_JSON_CHARS = MAX_TEXT_CHARS + 4096
    private const val MAX_NESTING = 64
    private const val MAX_ID_CHARS = 256
    private const val MAX_PATH_CHARS = 4096

    fun root(body: String): JsonObject {
        if (body.length > MAX_JSON_CHARS) throw V2WireException("JSON exceeds size limit")
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else
                when (char) {
                    '"' -> quoted = true
                    '{',
                    '[' ->
                        if (++depth > MAX_NESTING)
                            throw V2WireException("JSON exceeds nesting limit")
                    '}',
                    ']' -> depth--
                }
        }
        return try {
            Json.parseToJsonElement(body) as? JsonObject
                ?: throw V2WireException("root must be an object")
        } catch (_: IllegalArgumentException) {
            throw V2WireException("invalid JSON")
        }
    }

    fun output(body: JsonObject): String =
        body.toString().also {
            if (it.length > MAX_JSON_CHARS) throw V2WireException("JSON payload exceeds size limit")
        }

    fun id(value: String): String =
        value.also {
            if (it.isBlank() || it.length > MAX_ID_CHARS)
                throw V2WireException("identity is invalid")
        }

    /**
     * Filesystem paths are not IDs. Support POSIX, drive-absolute and UNC hosts within a fixed
     * budget.
     */
    fun path(value: String): String =
        value.also {
            val driveAbsolute =
                it.length >= 3 &&
                    it[0].isLetter() &&
                    it[1] == ':' &&
                    (it[2] == '\\' || it[2] == '/')
            val uncAbsolute = it.startsWith("\\\\")
            if (
                it.length > MAX_PATH_CHARS ||
                    '\u0000' in it ||
                    !(it.startsWith("/") || driveAbsolute || uncAbsolute)
            )
                throw V2WireException("absolute path is invalid or exceeds size limit")
        }

    fun JsonObject.obj(name: String): JsonObject =
        this[name] as? JsonObject ?: throw V2WireException("$name must be an object")

    fun JsonObject.array(name: String) =
        this[name] as? kotlinx.serialization.json.JsonArray
            ?: throw V2WireException("$name must be an array")

    fun JsonObject.string(name: String): String {
        val primitive =
            this[name] as? JsonPrimitive ?: throw V2WireException("$name must be a string")
        return if (primitive.isString) primitive.content
        else throw V2WireException("$name must be a string")
    }

    fun JsonObject.optionalString(name: String): String? =
        if (containsKey(name)) string(name) else null

    fun JsonObject.boolean(name: String): Boolean {
        val primitive =
            this[name] as? JsonPrimitive ?: throw V2WireException("$name must be a boolean")
        if (primitive.isString) throw V2WireException("$name must be a boolean")
        return primitive.content.toBooleanStrictOrNull()
            ?: throw V2WireException("$name must be a boolean")
    }
}
