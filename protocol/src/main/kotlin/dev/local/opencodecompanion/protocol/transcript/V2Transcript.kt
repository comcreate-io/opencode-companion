package dev.local.opencodecompanion.protocol.transcript

import dev.local.opencodecompanion.protocol.SessionKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Durable, observed 1.18.32 session events only. Transient overlays are decoded separately. */
object V2Transcript {
    private const val MAX_JSON_CHARS = 1_048_576
    private const val MAX_NESTING = 64
    private const val MAX_ID_CHARS = 256

    fun history(body: String, expected: SessionKey): TranscriptPage {
        val root = root(body)
        val items = root.array("data")
        if (items.size > 100) invalid("history page exceeds event limit")
        return TranscriptPage(
            items.map { item ->
                val rawJson = item.toString()
                TranscriptRecord(rawJson, event(rawJson, expected))
            },
            root.boolean("hasMore"),
        )
    }

    fun event(body: String, expected: SessionKey): TranscriptDecode {
        val root = root(body)
        val type = root.string("type")
        val durable = root.obj("durable")
        val sequence = durable.positiveLong("seq")
        val version = durable.positiveLong("version")
        if (durable.id("aggregateID") != expected.sessionId.value) invalid("aggregate ID mismatch")
        val supportedVersion =
            if (type == "session.next.step.ended" || type == "session.next.step.failed") 2L else 1L
        if (type !in TYPES || version != supportedVersion)
            return TranscriptDecode.Unsupported(type, version, sequence)
        val data = root.obj("data")
        if (data.id("sessionID") != expected.sessionId.value) invalid("event session ID mismatch")
        data.long("timestamp")
        val kind =
            when (type) {
                "session.next.prompt.admitted" ->
                    Kind.Prompt(
                        data.id("messageID"),
                        data.obj("prompt").string("text"),
                        data.delivery(),
                        false,
                    )
                "session.next.prompted" ->
                    Kind.Prompt(
                        data.id("messageID"),
                        data.obj("prompt").string("text"),
                        data.delivery(),
                        true,
                    )
                "session.next.step.started" -> {
                    val model = data.obj("model")
                    Kind.StepStarted(
                        data.id("assistantMessageID"),
                        data.id("agent"),
                        model.id("providerID"),
                        model.id("id"),
                        model.optionalString("variant"),
                    )
                }
                "session.next.step.ended" -> {
                    val tokens = data.obj("tokens")
                    val cache = tokens.obj("cache")
                    Kind.StepEnded(
                        data.id("assistantMessageID"),
                        data.string("finish"),
                        data.number("cost"),
                        TokenTotals(
                            tokens.number("input"),
                            tokens.number("output"),
                            tokens.number("reasoning"),
                            cache.number("read"),
                            cache.number("write"),
                        ),
                    )
                }
                "session.next.step.failed" ->
                    Kind.StepFailed(data.id("assistantMessageID"), data.error())
                "session.next.text.started" ->
                    Kind.TextStarted(data.id("assistantMessageID"), data.id("textID"))
                "session.next.text.ended" ->
                    Kind.TextEnded(
                        data.id("assistantMessageID"),
                        data.id("textID"),
                        data.string("text"),
                    )
                "session.next.tool.input.started" ->
                    Kind.ToolInputStarted(
                        data.id("assistantMessageID"),
                        data.id("callID"),
                        data.id("name"),
                    )
                "session.next.tool.input.ended" ->
                    Kind.ToolInputEnded(
                        data.id("assistantMessageID"),
                        data.id("callID"),
                        data.string("text"),
                    )
                "session.next.tool.called" ->
                    Kind.ToolCalled(
                        data.id("assistantMessageID"),
                        data.id("callID"),
                        data.id("tool"),
                        data.obj("input"),
                        data.obj("provider").boolean("executed"),
                    )
                "session.next.tool.success" ->
                    Kind.ToolSuccess(
                        data.id("assistantMessageID"),
                        data.id("callID"),
                        data.obj("structured"),
                        data.array("content"),
                        data.optionalArray("outputPaths"),
                        data.obj("provider").boolean("executed"),
                    )
                "session.next.tool.failed" ->
                    Kind.ToolFailed(
                        data.id("assistantMessageID"),
                        data.id("callID"),
                        data.error(),
                        data.obj("provider").boolean("executed"),
                    )
                else -> error("unreachable")
            }
        return TranscriptDecode.Supported(
            DurableTranscriptEvent(
                expected,
                root.id("id"),
                sequence,
                version,
                kind,
                data,
                body.length,
            )
        )
    }

    private val TYPES =
        setOf(
            "session.next.prompt.admitted",
            "session.next.prompted",
            "session.next.step.started",
            "session.next.step.ended",
            "session.next.step.failed",
            "session.next.text.started",
            "session.next.text.ended",
            "session.next.tool.input.started",
            "session.next.tool.input.ended",
            "session.next.tool.called",
            "session.next.tool.success",
            "session.next.tool.failed",
        )

    private fun root(body: String): JsonObject {
        if (body.length > MAX_JSON_CHARS) invalid("JSON exceeds size limit")
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
                    '[' -> if (++depth > MAX_NESTING) invalid("JSON exceeds nesting limit")
                    '}',
                    ']' -> depth--
                }
        }
        return try {
            Json.parseToJsonElement(body) as? JsonObject ?: invalid("root must be an object")
        } catch (_: IllegalArgumentException) {
            invalid("invalid JSON")
        }
    }

    private fun JsonObject.obj(name: String) =
        this[name] as? JsonObject ?: invalid("$name must be an object")

    private fun JsonObject.array(name: String) =
        this[name] as? JsonArray ?: invalid("$name must be an array")

    private fun JsonObject.optionalArray(name: String): JsonArray? =
        if (containsKey(name)) array(name) else null

    private fun JsonObject.string(name: String): String {
        val value = this[name] as? JsonPrimitive ?: invalid("$name must be a string")
        return if (value.isString) value.contentOrNull ?: invalid("$name must be a string")
        else invalid("$name must be a string")
    }

    private fun JsonObject.optionalString(name: String): String? =
        if (containsKey(name)) string(name) else null

    private fun JsonObject.id(name: String): String =
        string(name).also {
            if (it.isBlank() || it.length > MAX_ID_CHARS) invalid("$name is invalid")
        }

    private fun JsonObject.long(name: String): Long {
        val value = this[name] as? JsonPrimitive ?: invalid("$name must be an integer")
        if (value.isString || value.content.any { it == '.' || it == 'e' || it == 'E' })
            invalid("$name must be an integer")
        return value.longOrNull ?: invalid("$name must be an integer")
    }

    private fun JsonObject.positiveLong(name: String) =
        long(name).also { if (it <= 0) invalid("$name must be positive") }

    private fun JsonObject.number(name: String): Double {
        val value = this[name] as? JsonPrimitive ?: invalid("$name must be a number")
        if (value.isString) invalid("$name must be a number")
        return value.doubleOrNull?.takeIf { it.isFinite() } ?: invalid("$name must be finite")
    }

    private fun JsonObject.boolean(name: String): Boolean {
        val value = this[name] as? JsonPrimitive ?: invalid("$name must be a boolean")
        if (value.isString) invalid("$name must be a boolean")
        return value.booleanOrNull ?: invalid("$name must be a boolean")
    }

    private fun JsonObject.delivery() =
        string("delivery").also {
            if (it != "steer" && it != "queue") invalid("unsupported delivery")
        }

    private fun JsonObject.error(): TranscriptError {
        val error = obj("error")
        return TranscriptError(error.id("type"), error.string("message"))
    }

    private fun invalid(message: String): Nothing = throw TranscriptDecodeException(message)
}

class TranscriptDecodeException(message: String) : IllegalArgumentException(message)

/** Full bounded envelope for atomic raw journal storage alongside its validated interpretation. */
data class TranscriptRecord(val rawJson: String, val decoded: TranscriptDecode)

data class TranscriptPage(val records: List<TranscriptRecord>, val hasMore: Boolean) {
    val events: List<TranscriptDecode>
        get() = records.map { it.decoded }
}

sealed interface TranscriptDecode {
    data class Supported(val event: DurableTranscriptEvent) : TranscriptDecode

    data class Unsupported(val type: String, val version: Long, val sequence: Long) :
        TranscriptDecode
}

/** raw is the full data object, excluding optional transport-only location metadata. */
data class DurableTranscriptEvent(
    val session: SessionKey,
    val id: String,
    val sequence: Long,
    val version: Long,
    val kind: Kind,
    val raw: JsonObject,
    val wireChars: Int,
) {
    /** Replay identity ignores serialization whitespace, but preserves every payload field. */
    fun sameContentAs(other: DurableTranscriptEvent): Boolean =
        session == other.session &&
            id == other.id &&
            sequence == other.sequence &&
            version == other.version &&
            kind == other.kind &&
            raw == other.raw
}

data class TranscriptError(val type: String, val message: String)

data class TokenTotals(
    val input: Double,
    val output: Double,
    val reasoning: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

sealed interface Kind {
    data class Prompt(
        val messageId: String,
        val text: String,
        val delivery: String,
        val prompted: Boolean,
    ) : Kind

    data class StepStarted(
        val messageId: String,
        val agent: String,
        val providerId: String,
        val modelId: String,
        val variant: String?,
    ) : Kind

    data class StepEnded(
        val messageId: String,
        val finish: String,
        val cost: Double,
        val tokens: TokenTotals,
    ) : Kind

    data class StepFailed(val messageId: String, val error: TranscriptError) : Kind

    data class TextStarted(val messageId: String, val textId: String) : Kind

    data class TextEnded(val messageId: String, val textId: String, val text: String) : Kind

    data class ToolInputStarted(val messageId: String, val callId: String, val name: String) : Kind

    data class ToolInputEnded(val messageId: String, val callId: String, val text: String) : Kind

    data class ToolCalled(
        val messageId: String,
        val callId: String,
        val tool: String,
        val input: JsonObject,
        val providerExecuted: Boolean,
    ) : Kind

    data class ToolSuccess(
        val messageId: String,
        val callId: String,
        val structured: JsonObject,
        val content: JsonArray,
        val outputPaths: JsonArray?,
        val providerExecuted: Boolean,
    ) : Kind

    data class ToolFailed(
        val messageId: String,
        val callId: String,
        val error: TranscriptError,
        val providerExecuted: Boolean,
    ) : Kind
}
