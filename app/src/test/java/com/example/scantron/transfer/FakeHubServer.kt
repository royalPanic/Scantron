package com.example.scantron.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal stand-in for the desktop hub, built on [ServerSocket] so [TransferClient] is tested
 * against a real socket rather than a mock.
 *
 * MockWebServer would do the same job, but it is a new test dependency to keep patched for three
 * verbs and one request body. This is a few dozen lines and it speaks the wire contract directly,
 * which means the contract is asserted here rather than assumed.
 *
 * Records what it received on the request line, the Content-Type, and the **raw request body
 * bytes**, so a test can assert that `push` put on the wire exactly the string it was handed -
 * that is the property the whole feature rests on.
 *
 * Headers are parsed byte by byte rather than through a [java.io.BufferedReader]: a reader would
 * buffer ahead into the body and the byte-exactness assertion would stop being meaningful.
 */
class FakeHubServer(
    /** Per-request response. Return `null` to send nothing at all and force a read timeout. */
    private val respond: (request: CapturedRequest) -> Response?,
) : AutoCloseable {

    data class CapturedRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        /** Raw body bytes decoded as UTF-8, so a test can compare against the original string. */
        val body: String,
    )

    data class Response(
        val statusCode: Int,
        val body: String,
        val contentType: String = "application/json",
    )

    private val running = AtomicBoolean(true)
    private val received = LinkedBlockingQueue<CapturedRequest>()

    private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    /** The ephemeral port this server is listening on, for handing to [TransferClient]. */
    val port: Int get() = serverSocket.localPort

    init {
        Thread({ acceptLoop() }, "fake-hub-server").apply { isDaemon = true }.start()
    }

    /** The next request the server received, or null if none arrives within [timeoutMs]. */
    fun takeRequest(timeoutMs: Long = 5_000): CapturedRequest? =
        received.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                // Expected on close(): closing the server socket is what unblocks the accept.
                return
            }
            // One connection at a time, matching the hub's documented behaviour, which also keeps
            // the assertions deterministic without any test-side locking.
            Thread({ handle(socket) }, "fake-hub-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_SO_TIMEOUT_MS
            val input = socket.getInputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1) ?: return

            var contentType: String? = null
            var contentLength = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                val separator = header.indexOf(':')
                if (separator <= 0) continue
                when (header.substring(0, separator).trim().lowercase()) {
                    "content-type" -> contentType = header.substring(separator + 1).trim()
                    "content-length" ->
                        contentLength = header.substring(separator + 1).trim().toIntOrNull() ?: 0
                }
            }

            val body = readExactly(input, contentLength)

            val captured = CapturedRequest(method, path, contentType, String(body, Charsets.UTF_8))
            received.put(captured)

            val response = respond(captured)
            if (response == null) {
                // Deliberately answer nothing and keep the socket open, so the client's read
                // timeout fires instead of the test sleeping for the full duration twice.
                Thread.sleep(NO_RESPONSE_HOLD_MS)
                return
            }

            val payload = response.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${response.statusCode} ${reasonFor(response.statusCode)}\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            out.flush()
        } catch (e: IOException) {
            // The client hung up, or the test tore the server down mid-request. Nothing to assert.
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Reads one CRLF-terminated line as raw bytes and returns it decoded as UTF-8, or null at
     * end of stream.
     */
    private fun readLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>(32)
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.UTF_8)
            if (b == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last().toInt() == '\r'.code) bytes.removeAt(bytes.size - 1)
                return String(bytes.toByteArray(), Charsets.UTF_8)
            }
            bytes.add(b.toByte())
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val chunk = input.read(buffer, read, count - read)
            if (chunk < 0) break
            read += chunk
        }
        return if (read == count) buffer else buffer.copyOf(read)
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
    }

    private fun reasonFor(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private companion object {
        const val SOCKET_SO_TIMEOUT_MS = 5_000
        const val NO_RESPONSE_HOLD_MS = 30_000L
    }
}

/**
 * A loopback port with nothing listening on it.
 *
 * Held open for the lifetime of the test and only then closed, so the port cannot be recycled by
 * something else between releasing it and the client connecting - that race would make the
 * connection-refused test flaky rather than deterministic.
 */
class ReservedClosedPort private constructor(private val socket: ServerSocket) : AutoCloseable {

    val port: Int get() = socket.localPort

    /** Releases the port. Any later connect attempt is refused. */
        fun release() {
            runCatching { socket.close() }
        }

        override fun close() = release()

    companion object {
        suspend fun reserve(): ReservedClosedPort = withContext(Dispatchers.IO) {
            ReservedClosedPort(ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")))
        }
    }
}