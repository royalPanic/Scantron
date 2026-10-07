package com.example.scantron.transfer

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A document the desktop pushed to this device, staged but *not* yet imported.
 *
 * The split is the whole point of this class. A desktop push carries the same document a USB
 * export would, and importing it clears and replaces the device's whole database - so a push
 * arrives as [document] and the operator confirms it, exactly as `getFromDesktop` already does.
 * Treating an inbound push as a completed import would let anyone on the warehouse network
 * replace a day's scanning with a single unauthenticated request.
 */
data class StagedPush(
    val document: String,
    val containerCount: Int,
    val itemCount: Int,
    /** Address the desktop pushed from, for the confirmation text and the log. */
    val fromHost: String,
)

/**
 * Accepts a document pushed from the desktop, the reverse of [TransferClient].
 *
 * The normal topology is the desktop hosting and this device connecting, because a CK65 cannot be
 * relied on to hold a listening socket while docked or asleep. This is the other direction, for
 * the case the desktop's *Send to handheld* button covers: the operator is at the desk and the
 * scanner is somewhere on the same network, so the desk dials the scanner rather than waiting for
 * someone to walk over to it.
 *
 * Deliberately not a background service. It runs only while [start] has been called and the
 * transfer screen is open, for the same reason [Discovery] acquires its multicast lock only while
 * the screen is up: a listening socket on a shared warehouse network is a way for any device on
 * that network to push a document at this scanner, and leaving one open indefinitely is how that
 * becomes a standing hole rather than a deliberate, operator-initiated exchange.
 *
 * The endpoint is `POST /receive`, carrying the unmodified export document. No envelope and no
 * base64, exactly as [TransferClient.push] does it, so a document that arrived over Wi-Fi and the
 * same document moved on a USB stick remain interchangeable.
 */
class TransferListener(
    private val port: Int = DEFAULT_PORT,
) {

    private val tag = "TransferListener"

    private val running = AtomicBoolean(false)

    private var socket: ServerSocket? = null

    /** Suppresses a stale-request callback after [stop]. */
    @Volatile
    private var accepting = false

    /**
     * One inbound push, already validated and parsed, for the ViewModel to confirm.
     *
     * Invoked on the IO dispatcher. The ViewModel is responsible for hopping to the main thread,
     * because it owns state Compose reads.
     */
    @Volatile
    var onPushReceived: ((StagedPush) -> Unit)? = null

    /** True while the socket is bound and able to accept. Bound by [start], cleared by [stop]. */
    val isListening: Boolean get() = running.get()

    /**
     * The port actually bound, which differs from [DEFAULT_PORT] only when port 0 was asked for.
     *
     * Read from the socket rather than echoing the request back: the screen tells the operator
     * which port to use, and a number that does not match what is listening is worse than no
     * number at all. Tests rely on it for the same reason.
     */
    val boundPort: Int get() = socket?.localPort ?: port

    /**
     * Binds the port and begins accepting, reporting a refusal through the return value rather
     * than throwing.
     *
     * A refused bind is the likely failure - another copy of the screen is open, or the port is
     * taken by something else - and an operator staring at a button that silently does nothing has
     * no way to diagnose it. The reason is returned, and also kept on [problem].
     *
     * Binding and serving happen together here, and the accept loop is already running by the time
     * this returns. A caller that reported "listening" while the loop was still starting would be
     * reporting something that might not yet be true.
     */
    fun start(): String? {
        if (running.get()) return null

        val server = try {
            ServerSocket(port, 0, InetAddress.getByName("0.0.0.0"))
        } catch (e: IOException) {
            Log.w(tag, "Could not bind port $port", e)
            running.set(false)
            val reason = "Could not listen on port $port: ${e.message ?: "the port is in use"}. " +
                "Another app may already be listening."
            problem = reason
            return reason
        }

        socket = server
        running.set(true)
        accepting = true
        problem = null

        Thread({ acceptLoop() }, "scantron-transfer-listener").apply { isDaemon = true }.start()

        Log.i(tag, "Listening for a desktop push on port ${server.localPort}")
        return null
    }

    /** Why the listener is not listening, or null when it is. Shown to the operator. */
    @Volatile
    var problem: String? = null
        private set

    /**
     * Runs the accept loop until [stop] is called.
     *
     * A dedicated daemon thread rather than a coroutine, deliberately. A listening socket is a
     * long-lived resource whose loop has no natural suspension points, and a plain thread makes the
     * two properties that matter explicit: it parks on [ServerSocket.accept] for as long as the
     * device is listening, and it dies with the process without needing to be cancelled from a
     * lifecycle the operator cannot see. Mirrors `FakeHubServer`'s accept loop on the other side of
     * this contract for the same reason.
     */
    private fun acceptLoop() {
        val server = socket ?: return

        while (accepting) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                // Expected on stop(): closing the server socket is what unblocks the accept, and
                // without this the loop would spin on a closed socket logging the same line.
                if (accepting) Log.w(tag, "Accept failed", e)
                break
            }

            // One connection at a time, on this thread. A push replaces the device's inventory, so
            // two overlapping would stage two documents for one operator to choose between - the
            // hub on the desktop is sequential for the same reason.
            try {
                handle(client)
            } catch (e: IOException) {
                Log.w(tag, "A push connection failed", e)
            } catch (e: Exception) {
                // Last-resort net. An exception escaping here would kill the accept loop and leave
                // the screen reporting "listening" for a socket nobody is serving - the exact
                // silent failure this path exists to prevent.
                Log.e(tag, "A push handler failed", e)
                runCatching { respond(client, 500, "This device could not accept the transfer.") }
            }
        }
    }

    /**
     * Writes a reply and leaves the connection to the caller, which owns the socket.
     *
     * Every response carries its Content-Length, because a client that trusts a missing one reads
     * straight past the reply into whatever the socket says next - which on a keep-alive
     * connection is the *next* response, and produces a transfer that reports the wrong counts.
     *
     * Content type is passed in rather than fixed because this endpoint answers in two
     * languages: JSON for the acknowledgement, and plain text for every refusal. The desktop
     * shows a refusal body to the operator verbatim, so those have to read as sentences.
     */
    private fun respond(
        client: Socket,
        status: Int,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        runCatching {
            val payload = body.toByteArray(Charsets.UTF_8)
            client.getOutputStream().use { raw ->
                BufferedOutputStream(raw).use { out ->
                    out.write(
                        ("HTTP/1.1 $status ${statusText(status)}\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray(
                            Charsets.US_ASCII,
                        ),
                    )
                    out.write(payload)
                    out.flush()
                }
            }
        }.onFailure { Log.w(tag, "Could not answer a push on the socket", it) }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val input = socket.getInputStream()

            val requestLine = readLine(input)
            if (requestLine == null) {
                Log.w(tag, "A push arrived with an empty request line")
                return
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)
            val path = parts.getOrNull(1)

            if (!method.equals("POST", ignoreCase = true) || path?.substringBefore('?') != PATH) {
                respond(socket, 404, "This device only accepts POST $PATH. The desktop may be the wrong version.")
                return
            }

            val contentLength = readHeaders(input)
            if (contentLength > MAX_BODY_BYTES) {
                // Refused on the declared length before a byte is read, so an oversized request
                // never becomes a large string in memory on a warehouse scanner.
                respond(socket, 413, "That document is too large to receive. Export it to a file instead.")
                return
            }

            val body = readExactly(input, contentLength)
            if (body.isEmpty()) {
                respond(socket, 400, "The desktop sent an empty document. Nothing was changed.")
                return
            }

            val document = String(body, Charsets.UTF_8)
            val counts = PullPreviewCounts.from(document)
            if (counts == null) {
                respond(socket, 400, "That is not a Scantron document this device can read. Nothing was changed.")
                return
            }

            // Parsed and counted, but deliberately NOT imported: the operator confirms it first,
            // exactly as a pull does. Importing here would make an unauthenticated request on the
            // warehouse network a way to erase a day's scanning.
            val from = socket.inetAddress?.hostAddress ?: "the desktop"
            Log.i(tag, "Staged a push from $from: ${counts.containerCount} containers")

            // Accepted, and the counts echoed back so the desktop can report what arrived.
            respond(
                socket,
                200,
                """{"ok":true,"containers":${counts.containerCount},"items":${counts.itemCount},"staged":true}""",
                contentType = "application/json",
            )

            onPushReceived?.invoke(
                StagedPush(
                    document = document,
                    containerCount = counts.containerCount,
                    itemCount = counts.itemCount,
                    fromHost = from,
                ),
            )
        }
    }

    /** Reads headers, returning the declared body length. Unknown headers are ignored. */
    private fun readHeaders(input: InputStream): Int {
        var contentLength = 0
        while (true) {
            val header = readLine(input) ?: return contentLength
            if (header.isEmpty()) return contentLength

            val separator = header.indexOf(':')
            if (separator <= 0) continue
            if (header.substring(0, separator).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = header.substring(separator + 1).trim().toIntOrNull() ?: 0
            }
        }
    }

    /** Reads exactly [count] bytes, or whatever arrived before the socket ended. */
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)

        val buffer = ByteArray(minOf(count, MAX_BODY_BYTES))
        var read = 0
        while (read < buffer.size) {
            val chunk = input.read(buffer, read, buffer.size - read)
            if (chunk < 0) break
            read += chunk
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    /** Reads one CRLF-terminated line as raw bytes, or null at end of stream. */
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

    /** Closes the socket. Safe to call when not listening. */
    fun stop() {
        accepting = false
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        onPushReceived = null
        Log.i(tag, "Stopped listening for desktop pushes")
    }

    private fun statusText(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Status"
    }

    companion object {
        /**
         * Port a desktop pushes to. Distinct from the hub's 8756 and the 8757 discovery probe.
         *
         * Fixed by the contract on both sides rather than configurable: the desktop has to know
         * where to dial without being told, and an operator-tunable port is a support call.
         */
        const val DEFAULT_PORT = 8758

        /** The route the desktop posts to. */
        const val PATH = "/receive"

        /** Mirrors the hub's cap. A real export is well under a megabyte. */
        const val MAX_BODY_BYTES = 16 * 1024 * 1024

        /**
         * How long a push connection may stall.
         *
         * Bounds the damage from a peer that opens a socket and then says nothing - without it a
         * single such connection parks the sequential accept loop and every later push is refused
         * for as long as the peer feels like holding the socket open.
         */
        private const val READ_TIMEOUT_MS = 15_000
    }
}

/**
 * Counts of an inbound document, read before it is offered for confirmation.
 *
 * Deliberately lenient: this is a courtesy for the confirmation dialog and the desktop's
 * acknowledgement, not a validator. `ExportImportManager` remains the only thing allowed to decide
 * whether a document is importable, and it re-parses the document properly on confirmation - so
 * counting here can never authorise something that would then fail to import.
 */
internal object PullPreviewCounts {
    fun from(json: String): StagedPushCounts? = runCatching {
        val root = org.json.JSONObject(json)
        val containers = root.optJSONArray("containers") ?: return null
        var items = 0
        for (i in 0 until containers.length()) {
            items += containers.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
        }
        StagedPushCounts(containers.length(), items)
    }.getOrNull()
}

/** Container and item counts read off an inbound document. */
internal data class StagedPushCounts(val containerCount: Int, val itemCount: Int)
