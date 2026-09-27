package dev.local.opencodecompanion.protocol

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SseDecoderTest {
    @Test
    fun bytewiseUtf8AndBomProduceOneCompleteFrame() {
        val decoder = SseDecoder()
        val bytes = "\uFEFFdata: café ☕\n\n".toByteArray(StandardCharsets.UTF_8)
        val frames = bytes.flatMap { decoder.feed(byteArrayOf(it)) }

        assertEquals(listOf(SseFrame("café ☕")), frames)
        decoder.finish()
    }

    @Test
    fun mixedLineEndingsAndFieldsAcrossChunks() {
        val decoder = SseDecoder()
        assertTrue(decoder.feed("data: first\r".bytes()).isEmpty())
        val frames = decoder.feed("\ndata:  second\nevent: update\rid: 42\r\n\r".bytes())
        assertEquals(listOf(SseFrame("first\n second", "update", "42")), frames)
        assertEquals(listOf(SseFrame("next")), decoder.feed("\ndata: next\n\n".bytes()))
        assertEquals(listOf(SseFrame("last")), decoder.feed("data: last\n\n".bytes()))
    }

    @Test
    fun heartbeatsUnknownFieldsAndInvalidIdDoNotCreateEvents() {
        val decoder = SseDecoder()
        val frames =
            decoder.feed(
                ": ping\nretry: 100\nid: heartbeat\n\n".bytes() +
                    "data\nid: valid\nid: bad\u0000id\nunknown: ignored\n\n".bytes() +
                    "data: next\nid\n\n".bytes()
            )
        assertEquals(listOf(SseFrame("", id = "valid"), SseFrame("next", id = "")), frames)
    }

    @Test
    fun onlyTheFirstBomIsStrippedAndFieldsAreCaseSensitive() {
        val decoder = SseDecoder()
        assertEquals(
            listOf(SseFrame("\uFEFFkept")),
            decoder.feed("\uFEFF: initial\nData: ignored\ndata: \uFEFFkept\n\n".bytes()),
        )
    }

    @Test
    fun malformedAndTruncatedUtf8CloseDecoder() {
        val malformed = SseDecoder()
        val error =
            assertThrows(SseDecodeException::class.java) {
                malformed.feed(byteArrayOf(0x64, 0x61, 0x74, 0x61, 0x3a, 0xff.toByte(), 0x0a))
            }
        assertEquals(SseDecodeFailure.MALFORMED_UTF8, error.reason)
        assertThrows(IllegalStateException::class.java) { malformed.feed("data: x\n\n".bytes()) }

        val truncated = SseDecoder()
        truncated.feed("data: ".bytes() + byteArrayOf(0xe2.toByte(), 0x82.toByte()))
        assertEquals(
            SseDecodeFailure.MALFORMED_UTF8,
            assertThrows(SseDecodeException::class.java) { truncated.finish() }.reason,
        )
    }

    @Test
    fun boundsAreExplicitAndCompletedFramesSurviveLaterFailure() {
        val lineLimited = SseDecoder(maxLineBytes = 4)
        assertEquals(
            SseDecodeFailure.LINE_TOO_LARGE,
            assertThrows(SseDecodeException::class.java) { lineLimited.feed("data:".bytes()) }
                .reason,
        )

        val frameLimited = SseDecoder(maxLineBytes = 20, maxFrameBytes = 15)
        val error =
            assertThrows(SseDecodeException::class.java) {
                frameLimited.feed("data: a\n\ndata: too long\n\n".bytes())
            }
        assertEquals(SseDecodeFailure.FRAME_TOO_LARGE, error.reason)
        assertEquals(listOf(SseFrame("a")), error.completedFrames)
        assertThrows(IllegalStateException::class.java) { frameLimited.finish() }
    }

    @Test
    fun eofDiscardsAFrameWithoutFinalBlankLine() {
        val decoder = SseDecoder()
        assertEquals(
            listOf(SseFrame("complete")),
            decoder.feed("data: complete\n\ndata: pending\n".bytes()),
        )
        decoder.finish()
        assertThrows(IllegalStateException::class.java) { decoder.feed(byteArrayOf()) }
    }

    private fun String.bytes() = toByteArray(StandardCharsets.UTF_8)
}
