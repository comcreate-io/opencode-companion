package dev.local.opencodecompanion.protocol.transcript

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Plain text for the conversation timeline. Never serializes tool inputs or provider metadata. */
fun ToolSummary?.presentationOutput(): String? {
    val value =
        when (this) {
            is ToolSummary.Succeeded -> {
                val structuredText = structured["content"] as? JsonPrimitive
                if (structuredText?.isString == true) structuredText.content
                else
                    content
                        .mapNotNull { element ->
                            val part = element as? JsonObject ?: return@mapNotNull null
                            if ((part["type"] as? JsonPrimitive)?.content != "text")
                                return@mapNotNull null
                            (part["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        }
                        .joinToString("\n")
            }
            is ToolSummary.Failed -> error.message
            else -> return null
        }
    return if (value.length <= MAX_PRESENTATION_CHARS) value
    else value.take(MAX_PRESENTATION_CHARS) + "\n[output truncated]"
}

private const val MAX_PRESENTATION_CHARS = 100_000
