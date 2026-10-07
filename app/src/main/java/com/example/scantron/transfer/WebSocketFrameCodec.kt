package com.example.scantron.transfer

import java.security.MessageDigest
import java.util.Base64

/**
 * The RFC 6455 framing rules, as pure functions over byte arrays.
 *
 * Split out from the socket on purpose, mirroring how the desktop keeps `HubEndpoints` free of
 * `HttpListener`: a frame codec is where the interesting bugs live - a length that only appears
 * above 125 bytes, a mask the other side forgot to apply, a control frame interleaved with a data
 * frame - and every one of them is testable here with no socket, no thread and no timing. The
 * vectors in `WebSocketFrameCodecTest` are fixed bytes so the desktop codec can be pinned to the
 * same ones.
 *
 * Nothing here throws. A malformed frame arrives from the network as a matter of course, and the
 * codec's job is to say *why* in words an operator can read, not to unwind an exception through a
 * read loop.
 */
object WebSocketFrameCodec {

    /** Largest frame this client will assemble, so a hostile length cannot exhaust memory. */
    const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

    /** Opcodes this client understands. Anything else is refused rather than guessed at. */
    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    /** A decoded frame. [payload] is already unmasked, so callers never see the mask bit. */
    data class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean) {
        // ByteArray has identity equality, which is useless for a data class holding one; both sides
        // are written out so a test can compare frames directly.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Frame && opcode == other.opcode && fin == other.fin &&
                    payload.contentEquals(other.payload))

        override fun hashCode(): Int =
            (opcode * 31 + fin.hashCode()) * 31 + payload.contentHashCode()
    }

    /** Outcome of decoding, or the reason it could not be. */
    sealed interface DecodeResult {
        data class Ok(val frame: Frame) : DecodeResult

        /**
         * Not enough bytes are present yet.
         *
         * A distinct result rather than an error string, because the two are handled completely
         * differently: [Partial] means "read more and try again", and everything else means "this
         * connection is not speaking something we can use". Conflating them is how a decode loop
         * either spins on a fragmented read or drops a message that was merely split across two
         * `read()` calls.
         */
        data object Partial : DecodeResult

        data class Error(val reason: String) : DecodeResult
    }

    /**
     * Encodes a client-to-server frame.
     *
     * Client frames **must** be masked - a server is required to drop an unmasked one, and the
     * failure presents as a silently dead connection rather than as an error message - so masking is
     * not optional here and the mask key is generated inside this function rather than passed in.
     */
    fun encode(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        maskKey: ByteArray? = null,
    ): ByteArray {
        val key = maskKey ?: randomMaskKey()
        require(key.size == 4) { "A WebSocket mask key is exactly four bytes" }

        val header = mutableListOf<Byte>()

        // FIN and RSV1-3: no extensions are negotiated, so all three reserved bits stay clear.
        header += ((if (fin) 0x80 else 0x00) or (opcode and 0x0F)).toByte()

        val length = payload.size
        val maskBit = 0x80
        when {
            length < 126 -> header += (maskBit or length).toByte()
            length <= 0xFFFF -> {
                header += (maskBit or 126).toByte()
                header += ((length ushr 8) and 0xFF).toByte()
                header += (length and 0xFF).toByte()
            }
            else -> {
                header += (maskBit or 127).toByte()
                for (shift in 56 downTo 0 step 8) {
                    header += ((length.toLong() ushr shift) and 0xFF).toByte()
                }
            }
        }

        header += key.toList()

        val body = ByteArray(length) { index -> (payload[index].toInt() xor key[index % 4].toInt()).toByte() }

        return header.toByteArray() + body
    }

    /**
     * Decodes one frame from [buffer] starting at [offset].
     *
     * [bytesConsumed] is returned so a caller reading from a stream can advance past exactly one
     * frame and keep whatever arrived behind it. That is not a convenience: a read can return the
     * tail of one frame and the head of the next in a single call, and a decoder that assumed "one
     * read equals one frame" would corrupt every subsequent message on a busy connection.
     *
     * The server's frames are accepted whether or not they are masked. RFC 6455 forbids a server
     * from masking, but a *peer* that masks is harmless and this client is talking to a desktop that
     * may be built on a library with its own ideas - refusing here would fail a connection that is
     * otherwise perfectly workable.
     */
    fun decode(buffer: ByteArray, offset: Int = 0): Pair<DecodeResult, Int> {
        val available = buffer.size - offset
        if (available < 2) {
            return DecodeResult.Partial to 0
        }

        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF

        val fin = (b0 and 0x80) != 0
        val rsv = b0 and 0x70
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var payloadLength = (b1 and 0x7F).toLong()

        // Reserved bits are only meaningful with an extension negotiated in the handshake. None is,
        // so a set bit means the peer is speaking a protocol this build does not implement.
        if (rsv != 0) {
            return DecodeResult.Error("The desktop sent a frame using an extension this build does not support.") to 0
        }

        var cursor = offset + 2

        when (payloadLength) {
            126L -> {
                if (buffer.size - cursor < 2) return DecodeResult.Partial to 0
                payloadLength = ((buffer[cursor].toInt() and 0xFF) shl 8 or
                    (buffer[cursor + 1].toInt() and 0xFF)).toLong()
                cursor += 2
            }

            127L -> {
                if (buffer.size - cursor < 8) return DecodeResult.Partial to 0
                payloadLength = 0
                for (i in 0 until 8) {
                    payloadLength = (payloadLength shl 8) or (buffer[cursor + i].toLong() and 0xFF)
                }
                cursor += 8
                if (payloadLength < 0) {
                    return DecodeResult.Error("The desktop sent a frame with an impossible length.") to 0
                }
            }
        }

        if (payloadLength > MAX_FRAME_BYTES) {
            return DecodeResult.Error("The desktop sent a frame larger than this build accepts.") to 0
        }

        val maskKey = if (masked) {
            if (buffer.size - cursor < 4) return DecodeResult.Partial to 0
            val key = buffer.copyOfRange(cursor, cursor + 4)
            cursor += 4
            key
        } else {
            null
        }

        // Control frames are capped at 125 bytes by the spec, and must not be fragmented. Enforcing
        // both here means the frame loop never has to special-case a fragmented ping.
        val isControl = (opcode and 0x08) != 0
        if (isControl && (payloadLength > 125 || !fin)) {
            return DecodeResult.Error("The desktop sent a malformed control frame.") to 0
        }

        // A continuation carries the rest of a message this client never asked for: sends are
        // unfragmented in both directions, so a continuation means the peer is fragmenting
        // something. Refusing it is honest, and reassembling fragments the other side never sent
        // would be the wrong kind of tolerant.
        if (opcode == OPCODE_CONTINUATION) {
            return DecodeResult.Error("The desktop split a message across frames, which this build does not support.") to 0
        }

        val total = payloadLength.toInt()
        if (buffer.size - cursor < total) {
            // The header is complete but the body is not. More bytes are still on their way, so this
            // is the same "read more" answer as an incomplete header.
            return DecodeResult.Partial to 0
        }

        val payload = buffer.copyOfRange(cursor, cursor + total)
        if (maskKey != null) {
            for (i in 0 until payload.size) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return DecodeResult.Ok(Frame(opcode = opcode, payload = payload, fin = fin)) to (cursor + total - offset)
    }

    /**
     * The number of bytes [decode] needs at minimum before it can report a length, or null when the
     * header is still incomplete.
     *
     * Read loops need this: "keep reading until decode stops saying PARTIAL" cannot distinguish
     * "wait for one more byte" from "wait for a megabyte", and a loop that guesses wrong either spins
     * or stalls. Returning null means the header itself is not yet readable.
     */
    fun requiredBytesForHeader(buffer: ByteArray, offset: Int = 0): Int? {
        val available = buffer.size - offset
        if (available < 2) return null

        val b1 = buffer[offset + 1].toInt() and 0xFF
        val masked = (b1 and 0x80) != 0
        val maskBytes = if (masked) 4 else 0
        val lengthByte = b1 and 0x7F

        val lengthBytes = when {
            lengthByte < 126 -> 0
            lengthByte == 126 -> 2
            else -> 8
        }

        if (available < 2 + lengthBytes + maskBytes) return null

        var payloadLength = lengthByte.toLong()
        var cursor = offset + 2
        when (lengthBytes) {
            2 -> {
                payloadLength = ((buffer[cursor].toInt() and 0xFF) shl 8 or
                    (buffer[cursor + 1].toInt() and 0xFF)).toLong()
                cursor += 2
            }

            8 -> {
                payloadLength = 0
                for (i in 0 until 8) {
                    payloadLength = (payloadLength shl 8) or (buffer[cursor + i].toLong() and 0xFF)
                }
                cursor += 8
            }
        }
        cursor += maskBytes

        return (cursor - offset) + payloadLength.toInt()
    }

    /**
     * Encodes a close frame with the standard 1000 ("normal closure") status.
     *
     * A reason is never attached: the close status is the protocol's, and anything an operator needs
     * to read travels in a `bye` frame's `reason` before the socket goes down, where it can be shown
     * in a sentence rather than as a raw byte pair.
     */
    fun encodeClose(code: Int = 1000): ByteArray =
        encode(OPCODE_CLOSE, byteArrayOf(((code ushr 8) and 0xFF).toByte(), (code and 0xFF).toByte()))

    /** Encodes a keepalive ping, which some intermediaries require to hold a connection open. */
    fun encodePing(): ByteArray = encode(OPCODE_PING, ByteArray(0))

    /**
     * The `Sec-WebSocket-Key` for the opening handshake.
     *
     * A fresh random 16 bytes per connection, base64 encoded. It is not a secret and not
     * authentication - it exists so the server can prove it read the request - but it must still be
     * unpredictable per connection, which is why it is not a constant.
     */
    fun newHandshakeKey(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * The `Sec-WebSocket-Accept` value a server must return for [key].
     *
     * Verified rather than trusted, because there is no TLS on this link: the header is the only
     * evidence that the thing on the other end is a WebSocket server that read our request, as
     * opposed to some other service that happened to answer on the port. Without the check, a
     * misconfigured desktop would produce a stream this client would try to decode as frames.
     */
    fun expectedAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + RFC_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** The fixed GUID the accept digest is defined over, from RFC 6455 section 4.2.2. */
    private const val RFC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private fun randomMaskKey(): ByteArray {
        val key = ByteArray(4)
        java.security.SecureRandom().nextBytes(key)
        return key
    }
}
