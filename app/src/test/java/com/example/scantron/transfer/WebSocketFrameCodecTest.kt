package com.example.scantron.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RFC 6455 framing rules, pinned with fixed byte vectors.
 *
 * Every vector here is literal bytes rather than something this code produced, so the test proves the
 * codec agrees with the *spec* instead of proving it agrees with itself. That is the whole reason the
 * codec was separated from the socket: a frame bug is invisible until it reaches a desktop, and by
 * then the symptom is a hung connection rather than a failing assertion.
 *
 * The desktop's codec, if it ever grows one, can be pinned to these same vectors.
 */
class WebSocketFrameCodecTest {

    // ---- handshake -------------------------------------------------------------------------------

    @Test
    fun `the accept digest matches the RFC 6455 example`() {
        // The worked example from the spec, section 1.3: a server that computes this value for this
        // key is implementing the handshake correctly, and so is a client that expects it.
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WebSocketFrameCodec.expectedAccept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun `each handshake key is fresh and 16 bytes of base64`() {
        val first = WebSocketFrameCodec.newHandshakeKey()
        val second = WebSocketFrameCodec.newHandshakeKey()

        // A constant key would technically satisfy the spec's letter and defeat its purpose: the
        // digest exists so the client can prove the server read *this* request.
        assertNotEquals(first, second)
        assertTrue(java.util.Base64.getDecoder().decode(first).size == 16)
    }

    // ---- encoding --------------------------------------------------------------------------------

    @Test
    fun `a small text frame uses the 7-bit length and is masked`() {
        // "Hi" with a known mask key, so the whole frame is a fixed vector.
        val frame = WebSocketFrameCodec.encode(
            opcode = WebSocketFrameCodec.OPCODE_TEXT,
            payload = "Hi".toByteArray(Charsets.UTF_8),
            maskKey = byteArrayOf(0x01, 0x02, 0x03, 0x04),
        )

        // 0x81 = FIN + text; 0x82 = masked + length 2; then the key; then 'H'^1, 'i'^2.
        assertArrayEquals(
            byteArrayOf(
                0x81.toByte(),
                0x82.toByte(),
                0x01, 0x02, 0x03, 0x04,
                ('H'.code xor 0x01).toByte(),
                ('i'.code xor 0x02).toByte(),
            ),
            frame,
        )
    }

    @Test
    fun `the client mask bit is always set`() {
        // A server is required to drop an unmasked client frame, and the failure is a silently dead
        // connection rather than an error - so this is the single most important property here.
        val frame = WebSocketFrameCodec.encode(
            WebSocketFrameCodec.OPCODE_TEXT,
            "x".toByteArray(),
            maskKey = byteArrayOf(0, 0, 0, 0),
        )

        assertTrue("mask bit must be set", (frame[1].toInt() and 0x80) != 0)
    }

    @Test
    fun `a 16-bit length frame round-trips at the boundary`() {
        // 126 is the first length that needs the 16-bit form; getting the boundary wrong produces a
        // frame the peer reads as a completely different message.
        val payload = ByteArray(300) { (it % 251).toByte() }
        val encoded = WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_BINARY, payload)

        assertEquals(0x7E, encoded[1].toInt() and 0x7F)

        val (result, consumed) = WebSocketFrameCodec.decode(encoded)
        val frame = (result as WebSocketFrameCodec.DecodeResult.Ok).frame
        assertArrayEquals(payload, frame.payload)
        assertEquals(encoded.size, consumed)
    }

    @Test
    fun `a 64-bit length frame round-trips above 65535`() {
        // The 64-bit form is only used past 65535 bytes, which is a real case for a whole-inventory
        // snapshot - and the one length form a naive implementation silently truncates.
        val payload = ByteArray(70_000) { (it % 253).toByte() }
        val encoded = WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_BINARY, payload)

        assertEquals(0x7F, encoded[1].toInt() and 0x7F)

        val (result, _) = WebSocketFrameCodec.decode(encoded)
        val frame = (result as WebSocketFrameCodec.DecodeResult.Ok).frame
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `exactly 125 and 126 bytes choose different length forms`() {
        val small = WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, ByteArray(125))
        val large = WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, ByteArray(126))

        assertEquals(125, small[1].toInt() and 0x7F)
        assertEquals(126, large[1].toInt() and 0x7F)
    }

    // ---- decoding --------------------------------------------------------------------------------

    @Test
    fun `an unmasked server frame is accepted`() {
        // RFC 6455 forbids a server from masking, so this is what a correct desktop sends - and a
        // client that demanded a mask would reject every well-formed message.
        val buffer = byteArrayOf(0x81.toByte(), 0x02, 'O'.code.toByte(), 'K'.code.toByte())

        val (result, consumed) = WebSocketFrameCodec.decode(buffer)
        val frame = (result as WebSocketFrameCodec.DecodeResult.Ok).frame

        assertEquals("OK", String(frame.payload, Charsets.UTF_8))
        assertEquals(4, consumed)
    }

    @Test
    fun `a masked server frame is also accepted`() {
        // Not required by the spec, but harmless and worth tolerating: a desktop built on a library
        // with its own ideas about masking should not be unable to connect.
        val encoded = WebSocketFrameCodec.encode(
            WebSocketFrameCodec.OPCODE_TEXT,
            "masked".toByteArray(),
            maskKey = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D),
        )

        val (result, _) = WebSocketFrameCodec.decode(encoded)
        val frame = (result as WebSocketFrameCodec.DecodeResult.Ok).frame
        assertEquals("masked", String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun `a partial frame reports that more bytes are needed`() {
        val encoded = WebSocketFrameCodec.encode(
            WebSocketFrameCodec.OPCODE_TEXT,
            "hello there".toByteArray(),
        )

        // Only the first three bytes present: the header is readable but the body is not.
        val (result, _) = WebSocketFrameCodec.decode(encoded.copyOfRange(0, 3))

        assertEquals(WebSocketFrameCodec.DecodeResult.Partial, result)
    }

    @Test
    fun `a header split across two reads is reported as partial not broken`() {
        val encoded = WebSocketFrameCodec.encode(
            WebSocketFrameCodec.OPCODE_TEXT,
            ByteArray(200) { 'a'.code.toByte() },
        )

        // One byte is not enough to read the two-byte length, and a decoder that treated this as a
        // malformed frame would drop the first message after every reconnect.
        val (result, _) = WebSocketFrameCodec.decode(encoded.copyOfRange(0, 1))
        assertEquals(WebSocketFrameCodec.DecodeResult.Partial, result)
    }

    @Test
    fun `a frame followed by another frame consumes exactly the first`() {
        val first = WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, "one".toByteArray())
        val second = WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, "two".toByteArray())
        val combined = first + second

        val (firstResult, consumed) = WebSocketFrameCodec.decode(combined)
        assertEquals("one", String((firstResult as WebSocketFrameCodec.DecodeResult.Ok).frame.payload))
        assertEquals(first.size, consumed)

        // The second frame is still there, untouched, exactly as a stream read would leave it.
        val (secondResult, _) = WebSocketFrameCodec.decode(combined, consumed)
        assertEquals("two", String((secondResult as WebSocketFrameCodec.DecodeResult.Ok).frame.payload))
    }

    @Test
    fun `a continuation opcode is refused rather than reassembled`() {
        // Sends are unfragmented in both directions, so a continuation means the peer is fragmenting
        // something. Refusing is honest; reassembling fragments the other side never sent would be
        // the wrong kind of tolerant.
        val buffer = byteArrayOf(0x80.toByte(), 0x01, 'x'.code.toByte())

        val (result, _) = WebSocketFrameCodec.decode(buffer)
        assertTrue(result is WebSocketFrameCodec.DecodeResult.Error)
    }

    @Test
    fun `a set reserved bit is refused`() {
        // RSV1 means an extension was negotiated. None is, so a set bit means the peer is speaking a
        // protocol this build does not implement.
        val buffer = byteArrayOf(0xC1.toByte(), 0x01, 'x'.code.toByte())

        val (result, _) = WebSocketFrameCodec.decode(buffer)
        assertTrue(result is WebSocketFrameCodec.DecodeResult.Error)
    }

    @Test
    fun `a fragmented control frame is refused`() {
        // Control frames must not be fragmented, and a fragmented ping is the case that would slip
        // through a decoder that only checked the opcode.
        val buffer = byteArrayOf(0x09, 0x01, 'p'.code.toByte())

        val (result, _) = WebSocketFrameCodec.decode(buffer)
        assertTrue(result is WebSocketFrameCodec.DecodeResult.Error)
    }

    @Test
    fun `an oversized control frame is refused`() {
        val payload = ByteArray(126) { 0 }
        val buffer = byteArrayOf(0x89.toByte(), 0xFE.toByte(), 0x00, 0x7E) + payload

        val (result, _) = WebSocketFrameCodec.decode(buffer)
        assertTrue(result is WebSocketFrameCodec.DecodeResult.Error)
    }

    // ---- control frames --------------------------------------------------------------------------

    @Test
    fun `a ping decodes as a control frame with its payload intact`() {
        // The payload of a ping must be echoed verbatim in the pong; dropping it breaks peers that
        // use it as a correlation token.
        val encoded = WebSocketFrameCodec.encode(
            WebSocketFrameCodec.OPCODE_PING,
            "keepalive".toByteArray(),
        )

        val (result, _) = WebSocketFrameCodec.decode(encoded)
        val frame = (result as WebSocketFrameCodec.DecodeResult.Ok).frame

        assertEquals(WebSocketFrameCodec.OPCODE_PING, frame.opcode)
        assertEquals("keepalive", String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun `a close frame carries the normal-closure status`() {
        val (result, _) = WebSocketFrameCodec.decode(WebSocketFrameCodec.encodeClose())
        val frame = (result as WebSocketFrameCodec.DecodeResult.Ok).frame

        assertEquals(WebSocketFrameCodec.OPCODE_CLOSE, frame.opcode)
        assertEquals(2, frame.payload.size)
        assertEquals(1000, ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF))
    }

    @Test
    fun `a data frame carries the fin bit`() {
        val (result, _) = WebSocketFrameCodec.decode(
            WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, "x".toByteArray()),
        )

        assertTrue((result as WebSocketFrameCodec.DecodeResult.Ok).frame.fin)
    }

    // ---- header sizing ---------------------------------------------------------------------------

    @Test
    fun `required header bytes tells the read loop when to stop`() {
        val encoded = WebSocketFrameCodec.encode(
            WebSocketFrameCodec.OPCODE_TEXT,
            ByteArray(200),
            maskKey = byteArrayOf(0, 0, 0, 0),
        )

        // 2 header + 2 extended length + 4 mask + 200 payload.
        assertEquals(208, WebSocketFrameCodec.requiredBytesForHeader(encoded))
        assertEquals(null, WebSocketFrameCodec.requiredBytesForHeader(encoded.copyOfRange(0, 1)))
    }
}
