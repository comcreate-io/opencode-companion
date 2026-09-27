package dev.local.opencodecompanion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * One complete SSE block with data. [id] is only an explicit field in this block, not a replay
 * cursor.
 */
data class SseFrame(val data: String, val event: String? = null, val id: String? = null)

enum class SseDecodeFailure {
    MALFORMED_UTF8,
    LINE_TOO_LARGE,
    FRAME_TOO_LARGE,
}

/**
 * The decoder is closed after a failure. Frames completed earlier in the failing feed are supplied
 * here so a caller can process them before handling the failure; they are never silently dropped.
 */
class SseDecodeException(
    val reason: SseDecodeFailure,
    val completedFrames: List<SseFrame>,
    cause: Throwable? = null,
) : Exception("SSE decoding failed: $reason", cause)

/**
 * Incremental, strict UTF-8 SSE framing. Limits count bytes, with CRLF counted as one line ending.
 * A new decoder is required for each connection. `finish` validates trailing UTF-8 and discards any
 * block without a terminating blank line; neither `finish` nor an empty `feed` dispatches it.
 */
class SseDecoder(
    private val maxLineBytes: Int = 64 * 1024,
    private val maxFrameBytes: Int = 1024 * 1024,
) {
    init {
        require(maxLineBytes > 0)
        require(maxFrameBytes > 0)
    }

    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var hasData = false
    private var frameBytes = 0
    private var skipLf = false
    private var firstLine = true
    private var closed = false

    @Throws(SseDecodeException::class)
    fun feed(bytes: ByteArray): List<SseFrame> {
        check(!closed) { "SSE decoder is closed" }
        val completed = mutableListOf<SseFrame>()
        for (byte in bytes) {
            if (skipLf) {
                skipLf = false
                if (byte == LF) continue
            }
            if (frameBytes >= maxFrameBytes) fail(SseDecodeFailure.FRAME_TOO_LARGE, completed)
            frameBytes++
            when (byte) {
                CR,
                LF -> {
                    processLine(completed)
                    if (byte == CR) skipLf = true
                }
                else -> {
                    if (line.size() >= maxLineBytes)
                        fail(SseDecodeFailure.LINE_TOO_LARGE, completed)
                    line.write(byte.toInt())
                }
            }
        }
        return completed
    }

    @Throws(SseDecodeException::class)
    fun finish() {
        check(!closed) { "SSE decoder is closed" }
        // The final unterminated line cannot dispatch, but malformed or truncated bytes still fail.
        if (line.size() > 0) decodeLine(emptyList())
        closed = true
    }

    private fun processLine(completed: MutableList<SseFrame>) {
        val raw = decodeLine(completed)
        line.reset()
        val value = if (firstLine && raw.startsWith('\uFEFF')) raw.substring(1) else raw
        firstLine = false
        if (value.isEmpty()) {
            if (hasData) completed += SseFrame(data.toString(), event, id)
            data.setLength(0)
            event = null
            id = null
            hasData = false
            frameBytes = 0
            return
        }
        if (value[0] == ':') return
        val colon = value.indexOf(':')
        val field = if (colon < 0) value else value.substring(0, colon)
        val untrimmed = if (colon < 0) "" else value.substring(colon + 1)
        val fieldValue = if (untrimmed.startsWith(' ')) untrimmed.substring(1) else untrimmed
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(fieldValue)
                hasData = true
            }
            "event" -> event = fieldValue
            "id" -> if ('\u0000' !in fieldValue) id = fieldValue
        }
    }

    private fun decodeLine(completed: List<SseFrame>): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line.toByteArray()))
                .toString()
        } catch (error: CharacterCodingException) {
            fail(SseDecodeFailure.MALFORMED_UTF8, completed, error)
        }

    private fun fail(
        reason: SseDecodeFailure,
        completed: List<SseFrame>,
        cause: Throwable? = null,
    ): Nothing {
        closed = true
        throw SseDecodeException(reason, completed.toList(), cause)
    }

    private companion object {
        const val CR: Byte = 13
        const val LF: Byte = 10
    }
}
