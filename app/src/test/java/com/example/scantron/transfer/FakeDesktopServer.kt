package com.example.scantron.transfer

import com.example.scantron.sync.SyncJson
import com.example.scantron.sync.SyncMessage
import com.example.scantron.sync.SyncMessageType
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal fake desktop speaking the live-sync contract over a real loopback socket.
 *
 * Built on [ServerSocket] and the production [WebSocketFrameCodec] rather than on a mock, for the same
 * reason `FakeHubServer` is: it asserts the contract instead of assuming it. The client and the server
 * here are genuinely independent implementations of the handshake and the framing rules, so a test
 * that passes proves the two agree on the wire - which is exactly the property a hand-written codec
 * needs evidence for.
 *
 * The script is a list of [Step]s: each step sees the frame the client sent and decides what to reply
 * with. That keeps a multi-frame conversation readable as a list rather than as a state machine
 * scattered across callbacks.
 */
class FakeDesktopServer(
    /** Machine name reported in `paired`, matching what the real desktop sends. */
    private val desktopName: String = "DESKTOP-TEST",
    /** Framework the test drives once the handshake completes. */
    private val scenario: (server: Conversation) -> Unit,
) : AutoCloseable {

    private val running = AtomicBoolean(true)
    private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    /** Frames the server received, so a test can assert on what the client actually sent. */
    val received = LinkedBlockingQueue<SyncMessage>()

    /** How many times a client completed the handshake. Used to assert reconnect behaviour. */
    val connections = java.util.concurrent.atomic.AtomicInteger(0)

    val port: Int get() = serverSocket.localPort

    private var socket: Socket? = null

    init {
        Thread({ acceptLoop() }, "fake-desktop-server").apply { isDaemon = true }.start()
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        runCatching { serverSocket.close() }
    }

    /**
     * One connected desktop.
     *
     * Exposes the conversation in the direction a test cares about: read what the client sent, then
     * send what the desktop replies. A test therefore reads as the script of a sync session.
     */
    inner class Conversation internal constructor(private val socket: Socket) {

        private val input = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()

        @Volatile
        private var closed = false

        /** Blocks until the client sends a frame, or returns null on timeout/close. */
        fun nextMessage(timeoutMs: Long = 5_000): SyncMessage? {
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline && !closed) {
                // The socket timeout is shrunk to what is left of this call's budget, so a bounded
                // read is genuinely bounded. Without it a read sits on the socket's own (longer)
                // timeout, the caller blocks far past its deadline, and the conversation is then
                // judged closed for reasons that had nothing to do with the peer.
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                runCatching { socket.soTimeout = remaining }

                val text = readTextFrame()
                    ?: if (closed) return null else continue

                val message = parse(text) ?: continue
                received.add(message)
                return message
            }

            return null
        }

        /** Reads the next raw text frame, or null when the peer closes. Used by low-level tests. */
        fun readAnyText(): String? = readTextFrame()

        /** Sends [message] to the client as a text frame. */
        fun send(message: SyncMessage) {
            sendRaw(SyncJson.write(message))
        }

        fun sendRaw(text: String) {
            // Throwing rather than dropping: a scripted frame that never reaches the client is the
            // single hardest failure to diagnose here, because the test then fails on whatever the
            // client did not do rather than on the send that did not happen.
            check(!closed) { "The conversation is closed; cannot send ${text.take(80)}" }
            output.write(WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_TEXT, text.toByteArray()))
            output.flush()
        }

        /** Closes the socket, which the client sees as the peer going away. */
        fun disconnect() {
            closed = true
            runCatching { socket.close() }
        }

        /** Ends the conversation with a graceful close frame carrying [reason]. */
        fun sayGoodbye(reason: String) {
            send(SyncMessage(type = SyncMessageType.Bye, reason = reason))
            val payload = byteArrayOf(0x03, 0xE8.toByte()) + reason.toByteArray(Charsets.UTF_8)
            // Deliberately not marked closed: the client still has to read these two frames, and a
            // closed flag here would make the server drop the very bytes it just wrote. The client
            // closes its own socket when it sees the close frame, which is what unblocks the read.
            output.write(WebSocketFrameCodec.encode(WebSocketFrameCodec.OPCODE_CLOSE, payload))
            output.flush()
        }

        /** Reads one text frame, handling ping/close the way a well-behaved peer would. */
        private fun readTextFrame(): String? {
            var pending = ByteArray(0)
            val buffer = ByteArray(4096)

            while (!closed) {
                val (result, consumed) = WebSocketFrameCodec.decode(pending)

                when (result) {
                    is WebSocketFrameCodec.DecodeResult.Ok -> {
                        pending = pending.copyOfRange(consumed, pending.size)
                        val frame = result.frame

                        if (frame.opcode == WebSocketFrameCodec.OPCODE_CLOSE) {
                            closed = true
                            return null
                        }

                        if (frame.opcode == WebSocketFrameCodec.OPCODE_TEXT) {
                            return String(frame.payload, Charsets.UTF_8)
                        }

                        // Binary and control frames other than close carry nothing this script wants.
                    }

                    WebSocketFrameCodec.DecodeResult.Partial -> Unit

                    is WebSocketFrameCodec.DecodeResult.Error -> {
                        closed = true
                        return null
                    }
                }

                val read = try {
                    input.read(buffer)
                } catch (e: java.net.SocketTimeoutException) {
                    // Nothing arrived within this read's budget. Not a closure: the caller decides
                    // whether its own deadline has passed.
                    return null
                } catch (e: IOException) {
                    closed = true
                    return null
                }

                if (read == -1) {
                    closed = true
                    return null
                }

                if (read > 0) pending = pending + buffer.copyOfRange(0, read)
            }

            return null
        }

        private fun parse(text: String): SyncMessage? =
            (SyncJson.read(text) as? SyncJson.ReadResult.Parsed)?.message
    }

    private fun acceptLoop() {
        while (running.get()) {
            val accepted = try {
                serverSocket.accept()
            } catch (e: IOException) {
                return
            }

            socket = accepted
            connections.incrementAndGet()

            // One conversation at a time, matching the desktop's documented behaviour and keeping the
            // assertions deterministic without any test-side locking. A failed scenario closes the
            // socket rather than taking the JVM's test runner down with it.
            try {
                accepted.soTimeout = 10_000
                if (!performServerHandshake(accepted)) continue
                scenario(Conversation(accepted))
            } catch (e: Exception) {
                // Expected whenever a test disconnects mid-conversation to force a reconnect, so this
                // is swallowed rather than failing the run: the assertion that matters is on what the
                // client did next, not on the server noticing the socket went away.
            }
        }
    }

    /**
     * The server half of the upgrade, written byte-for-byte against the spec.
     *
     * Implementing it here rather than reusing anything from the client is the point: a handshake that
     * only round-trips against its own implementation proves nothing, and the accept digest is exactly
     * the value the desktop has to compute too.
     */
    private fun performServerHandshake(socket: Socket): Boolean {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val head = StringBuilder()
        var previousWasCr = false
        while (true) {
            val next = input.read()
            if (next == -1) return false
            val char = next.toChar()
            head.append(char)
            if (char == '\n' && previousWasCr && head.endsWith("\r\n\r\n")) break
            previousWasCr = char == '\r'
            if (head.length > 8 * 1024) return false
        }

        val key = head.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()["sec-websocket-key"]
            ?: return false

        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ${WebSocketFrameCodec.expectedAccept(key)}\r\n")
            append("\r\n")
        }

        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
        return true
    }

    /** Waits for the client to complete a handshake, up to [timeoutMs]. */
    fun awaitConnection(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connections.get() > 0) return true
            Thread.sleep(25)
        }
        return false
    }

    /** Drains frames the server received, for assertions. */
    fun drain(): List<SyncMessage> {
        val drained = mutableListOf<SyncMessage>()
        while (true) {
            val next = received.poll(0, TimeUnit.MILLISECONDS) ?: break
            drained += next
        }
        return drained
    }
}
