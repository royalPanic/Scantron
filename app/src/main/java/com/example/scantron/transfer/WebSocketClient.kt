package com.example.scantron.transfer

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Result of a live-sync session, shaped like [TransferResult] so the UI treats both the same way.
 *
 * [message] is written for an operator standing in a warehouse, never for a developer. Where the
 * peer explained itself - a `bye` with a reason, or the desktop's own plain-text error - that text is
 * surfaced **verbatim**; exception class names are deliberately never shown, because
 * "java.net.SocketTimeoutException" tells an operator nothing they can act on.
 */
sealed interface SyncResult {
    val message: String

    /** The peer closed the session gracefully with this reason. */
    data class Closed(val reason: String) : SyncResult {
        override val message: String get() = reason
    }

    /** The session ended for a reason that is not the peer's doing. */
    data class Failure(override val message: String) : SyncResult
}

/**
 * A raw RFC 6455 client over `GET /sync` on the desktop's transfer port.
 *
 * A raw [Socket] rather than `HttpURLConnection`, because the upgrade is the whole point:
 * `HttpURLConnection` cannot be handed a socket after the handshake and offers no way to keep
 * reading frames off it. Nothing else is added in its place - no OkHttp, no retrofit, no
 * serialization library - because the framing rules are a few dozen lines and the dependency would be
 * a supply-chain and versioning burden for a protocol this client only ever speaks in one direction.
 *
 * The handshake is **verified**, not trusted: the `Sec-WebSocket-Accept` digest is recomputed and
 * compared. There is no TLS on this link, so that header is the only evidence the thing on the other
 * end is a WebSocket server that actually read the request - without it, any service that happened to
 * answer on the port would produce a stream this client would try to decode as frames.
 *
 * All I/O runs on [ioDispatcher], every socket operation has a timeout, and the socket is closed in a
 * `finally` on every path. A warehouse dead spot will otherwise hold a connection open indefinitely
 * and the operator gets a frozen screen instead of an error.
 */
class WebSocketClient(
    private val host: String,
    private val port: Int = TransferClient.DEFAULT_PORT,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val tag = "WebSocketClient"

    private val endpoint: String = "ws://${host.trim()}:$port/sync"

    /**
     * Opens the connection, performs the handshake and then runs [onText] for every text frame
     * until the peer closes or [onText] returns false.
     *
     * [onText] is a suspend function and is called on the IO dispatcher, so it may do database work
     * directly - which is what lets the engine's session loop read as a straight-line conversation
     * instead of a state machine spread across callbacks.
     */
    suspend fun connectAndPump(
        onText: suspend (String) -> Boolean,
        onOpen: suspend (send: TextSender) -> Unit,
    ): SyncResult = withContext(ioDispatcher) {
        var socket: Socket? = null

        // Captured before the apply block: inside `Socket().apply {}` an unqualified `port` resolves
        // to Socket.getPort() - the *connected local* port, which is 0 before connect - rather than to
        // this class's property. That shadowing is silent and would dial port 0 on every attempt.
        val targetPort = port
        val targetHost = host.trim()

        try {
            socket = Socket().apply {
                // Both timeouts are set before connect, and both are non-zero: a null read timeout
                // means the loop below blocks forever on a desktop that has silently gone away.
                connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMs)
                soTimeout = readTimeoutMs
                // The frames this protocol exchanges are tiny and latency-sensitive; Nagle would add
                // up to 40 ms to every handshake frame for no benefit.
                tcpNoDelay = true
            }

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            performHandshake(input, output)
                ?: return@withContext SyncResult.Failure(
                    "The desktop answered $endpoint but is not speaking Scantron sync. " +
                        "Check that the desktop is running the same version.",
                )

            val sender = TextSender(output)
            try {
                onOpen(sender)
                pumpFrames(input, sender, onText)
            } finally {
                // Closing here rather than in the outer finally so the close frame is sent while the
                // socket is still usable; a socket closed underneath the write would surface as a
                // spurious failure on a session that ended perfectly well.
                runCatching { sender.close() }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(tag, "Timed out talking to $endpoint", e)
            SyncResult.Failure(
                "The desktop at ${host.trim()} stopped answering. It may have closed Scantron, " +
                    "or left the Wi-Fi.",
            )
        } catch (e: IOException) {
            Log.w(tag, "Could not reach $endpoint", e)
            SyncResult.Failure(
                "Could not reach the desktop at ${host.trim()}. Check the address and that the " +
                    "desktop has sharing switched on.",
            )
        } catch (e: Exception) {
            Log.e(tag, "Live sync failed unexpectedly", e)
            SyncResult.Failure("Live sync could not continue.")
        } finally {
            runCatching { socket?.close() }
        }
    }

    /**
     * Sends one text frame. Unfragmented, because a `SyncMessage` is a single JSON document and
     * splitting it would gain nothing but a reassembly path on both peers.
     */
    class TextSender internal constructor(private val output: OutputStream) {

        @Synchronized
        fun sendText(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            output.write(WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, bytes))
            output.flush()
        }

        @Synchronized
        fun sendPong() {
            output.write(WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_PONG, ByteArray(0)))
            output.flush()
        }

        @Synchronized
        fun close() {
            output.write(WebSocketFrameCodec.encodeClose())
            output.flush()
        }
    }

    /**
     * The `GET /sync` upgrade.
     *
     * Returns the negotiated accept value on success, or null when the peer declined. A non-101
     * status is read for its body and logged, because a desktop that says "no" in a sentence is far
     * easier to act on than a client that only reports a status code.
     */
    private fun performHandshake(input: InputStream, output: OutputStream): String? {
        val key = WebSocketFrameCodec.newHandshakeKey()
        val targetHost = host.trim()

        // Host carries the port only when it is non-default, which is what a browser would send and
        // what a strict server may expect.
        val request = buildString {
            append("GET $SYNC_PATH HTTP/1.1\r\n")
            append("Host: $targetHost:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }

        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val responseHead = readResponseHead(input)
            ?: return null

        val statusLine = responseHead.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains("101")) {
            Log.w(tag, "Handshake refused by $endpoint: $statusLine")
            return null
        }

        val headers = responseHead.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()

        if (headers["upgrade"]?.lowercase() != "websocket") {
            return null
        }

        val accept = headers["sec-websocket-accept"] ?: return null
        return if (accept == WebSocketFrameCodec.expectedAccept(key)) accept else null
    }

    /**
     * Reads the response head, up to and including the blank line.
     *
     * Byte-oriented rather than `BufferedReader`, because a reader would buffer into whatever bytes
     * the server sent *after* the handshake - and the first frame would then be sitting in a buffer
     * that is thrown away with the reader. That is a genuinely nasty failure: the connection is
     * established, the first frame is lost, and the session appears to hang.
     */
    private fun readResponseHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var previousWasCr = false

        while (true) {
            val next = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                return null
            }

            if (next == -1) return null
            val char = next.toChar()
            buffer.append(char)

            if (char == '\n' && previousWasCr && buffer.endsWith("\r\n\r\n")) {
                return buffer.toString()
            }
            previousWasCr = char == '\r'

            // A handshake head is a few hundred bytes; anything past this is not one.
            if (buffer.length > MAX_HEADER_BYTES) return null
        }
    }

    /**
     * The frame loop.
     *
     * Handles the three length forms, masking in both directions, and all three control frames. A ping
     * is answered with a pong immediately - deliberately not queued, because the whole point of a ping
     * is a timely reply. A close ends the loop and reports the peer's status as the outcome.
     */
    private suspend fun pumpFrames(
        input: InputStream,
        sender: TextSender,
        onText: suspend (String) -> Boolean,
    ): SyncResult {
        var pending = ByteArray(0)
        val readBuffer = ByteArray(READ_CHUNK)

        while (true) {
            val outcome = nextFrame(input, pending, readBuffer)
            pending = outcome.leftover
            val frame = outcome.frame ?: return SyncResult.Failure(outcome.error ?: "Live sync stopped.")

            when (frame.opcode) {
                WebSocketFrameCodec.OPCODE_TEXT -> {
                    // A BOM-free UTF-8 decode of a JSON document; a malformed byte sequence is
                    // replaced rather than thrown, so one bad frame cannot take the session down.
                    val text = String(frame.payload, Charsets.UTF_8)
                    if (!onText(text)) {
                        return SyncResult.Failure("Live sync stopped.")
                    }
                }

                WebSocketFrameCodec.OPCODE_PING -> sender.sendPong()

                WebSocketFrameCodec.OPCODE_PONG -> Unit // Keepalive acknowledged; nothing to do.

                WebSocketFrameCodec.OPCODE_CLOSE -> return SyncResult.Closed(closeReason(frame.payload))

                WebSocketFrameCodec.OPCODE_BINARY -> {
                    // The contract is text frames only. Refusing rather than decoding means a build
                    // mismatch reports itself instead of producing a JSON parse error somewhere far
                    // from the cause.
                    return SyncResult.Failure(
                        "The desktop sent a binary frame, which this build does not use. " +
                            "Both devices must be running the same Scantron version.",
                    )
                }

                else -> return SyncResult.Failure(
                    "The desktop sent a frame this build does not understand.",
                )
            }
        }
    }

    /** One decoded frame, or the reason there is none, plus whatever bytes are still unread. */
    private data class FrameOutcome(
        val frame: WebSocketFrameCodec.Frame?,
        val leftover: ByteArray,
        val error: String?,
    )

    /**
     * Reads until one whole frame is available, then decodes exactly one and returns the remainder.
     *
     * The retry loop is what makes partial reads work: a single `read` can return any number of bytes,
     * including the tail of one frame and the head of the next, so "one read, one frame" is an
     * assumption that holds on a quiet loopback and fails on a busy warehouse AP. Carrying the
     * remainder back out is what lets the next call decode the very next frame rather than throwing
     * away bytes that have already arrived.
     */
    private fun nextFrame(
        input: InputStream,
        pending: ByteArray,
        readBuffer: ByteArray,
    ): FrameOutcome {
        var buffer = pending

        while (true) {
            val (result, consumed) = WebSocketFrameCodec.decode(buffer)

            when (result) {
                is WebSocketFrameCodec.DecodeResult.Ok -> return FrameOutcome(
                    frame = result.frame,
                    leftover = buffer.copyOfRange(consumed, buffer.size),
                    error = null,
                )

                WebSocketFrameCodec.DecodeResult.Partial -> Unit // Fall through to read more.

                is WebSocketFrameCodec.DecodeResult.Error ->
                    return FrameOutcome(frame = null, leftover = ByteArray(0), error = result.reason)
            }

            // Decode wanted more bytes. Read once and grow the buffer by what arrived; a bounded frame
            // size is already enforced inside the codec, so this cannot grow without limit.
            val read = try {
                input.read(readBuffer)
            } catch (e: SocketTimeoutException) {
                // A read timeout here is a health signal, not a failure: the desktop sends nothing
                // while it has nothing to say, so the loop simply keeps waiting.
                continue
            } catch (e: IOException) {
                return FrameOutcome(frame = null, leftover = ByteArray(0), error = "The desktop closed the connection.")
            }

            if (read == -1) {
                return FrameOutcome(frame = null, leftover = ByteArray(0), error = "The desktop closed the connection.")
            }

            if (read == 0) continue

            buffer = buffer + readBuffer.copyOfRange(0, read)
        }
    }

    /**
     * The reason text from a close frame, in an operator's words.
     *
     * The 1000 status is "normal closure" and needs no explanation. Anything else is presented with
     * its code, because a peer that closed for a specific reason is telling us something useful and
     * swallowing it behind "connection closed" would waste it.
     */
    private fun closeReason(payload: ByteArray): String {
        if (payload.size < 2) {
            return "The desktop ended the sync."
        }

        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val text = if (payload.size > 2) {
            String(payload, 2, payload.size - 2, Charsets.UTF_8).trim()
        } else {
            ""
        }

        return when {
            text.isNotEmpty() -> text
            code == 1000 -> "The desktop ended the sync."
            else -> "The desktop ended the sync (code $code)."
        }
    }

    companion object {
        /** The path the desktop's live-sync endpoint answers on. */
        const val SYNC_PATH = "/sync"

        private const val READ_CHUNK = 8 * 1024
        private const val MAX_HEADER_BYTES = 8 * 1024

        public const val CONNECT_TIMEOUT_MS: Int = 10_000

        /**
         * Read timeout for the frame loop.
         *
         * Long enough that a desktop with nothing to say is not treated as dead - a live-sync session
         * is idle most of the time by design, since the whole point is that changes are rare - and
         * short enough that a genuinely vanished desktop is noticed within a minute.
         */
        public const val READ_TIMEOUT_MS: Int = 60_000
    }
}
