package com.example.scantron.transfer

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Client for the desktop transfer hub. Speaks the frozen wire contract:
 *
 * ```text
 * GET  /health -> 200 "scantron-hub/1 <deviceName>"
 * POST /push   -> body is the unmodified Scantron export document
 *               -> 200 {"ok":true,"containers":N,"items":M}
 * GET  /pull   -> 200, body is the unmodified Scantron export document
 * ```
 *
 * Built on `HttpURLConnection` rather than OkHttp/Retrofit on purpose: three verbs, one
 * request at a time, no connection pooling to tune. A third-party client would be a dependency
 * to keep patched for no capability this feature uses.
 *
 * The request and response bodies are the **raw export document** - no envelope, no base64, no
 * zip - so a document that arrived over Wi-Fi and the same document moved on a USB stick are
 * interchangeable, and neither side can tell which one it got.
 *
 * Every call is on [ioDispatcher] and never blocks the caller, and every call sets both connect
 * and read timeouts: a warehouse dead spot will otherwise hold the socket open indefinitely and
 * the operator gets a frozen screen instead of an error.
 */
class TransferClient(
    host: String,
    private val port: Int = DEFAULT_PORT,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val tag = "TransferClient"

    /**
     * The host with paste artefacts stripped, or null if nothing usable is left.
     *
     * `URL` does not validate the host at all - it accepts anything and lets the failure surface
     * later as a confusing network error - so the operator's input is normalised here instead.
     * See [sanitizeHost] for what that is protecting against.
     */
    private val host: String? = sanitizeHost(host)

    /** The address to name in an error, or empty when the input was unusable. */
    private val endpoint: String = host?.let { "http://$it:$port" }.orEmpty()

    /**
     * `GET /health`. The cheapest way to tell "wrong address" from "desktop isn't sharing" -
     * `/pull` and `/push` both fail for the same reasons but report it far less clearly.
     */
    suspend fun health(): TransferResult =
        request("GET", "/health", body = null)

    /**
     * `POST /push`. [json] is sent as UTF-8 bytes exactly as given - the caller must not
     * reformat or re-indent it, because the desktop parses the same document the USB path
     * produces and the bytes are the contract.
     */
    suspend fun push(json: String): TransferResult =
        request("POST", "/push", body = json)

    /**
     * `GET /pull`. On success the body is the desktop's export document; the caller is
     * responsible for feeding it to `importFromJsonString`, which is destructive.
     */
    suspend fun pull(): TransferResult =
        request("GET", "/pull", body = null)

    private suspend fun request(method: String, path: String, body: String?): TransferResult =
        withContext(ioDispatcher) {
            // Reached when the operator typed something with nothing usable in it. Reported
            // plainly: this is a typo, not a network fault, and conflating the two would send
            // someone off to check the Wi-Fi.
            val target = host
                ?: return@withContext TransferResult.Failure(
                    "That is not a desktop address. Type the IP the desktop is showing, " +
                        "like 192.168.1.50",
                )

            val url = URL("http", target, port, path)

            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    // The hub serves one request at a time and we never pipeline, so keep-alive
                    // buys nothing and only risks holding a socket against a laptop that has
                    // gone to sleep.
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setFixedLengthStreamingMode(body.toByteArray(StandardCharsets.UTF_8).size)
                    }
                }

                if (body != null) {
                    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }

                val status = connection.responseCode
                val payload = readPayload(connection, status)

                if (status in 200..299) {
                    TransferResult.Success(statusCode = status, body = payload, message = describeOk(path))
                } else {
                    // The hub's plain-text reason is the most useful thing in the failure path:
                    // "Nothing to send - open a document on the desktop first" tells the operator
                    // exactly what to do, where a generic error would not.
                    val reason = payload.trim().ifEmpty { defaultStatusText(status) }
                    Log.w(tag, "$method $path failed: HTTP $status - $reason")
                    TransferResult.Failure(message = "Desktop said: $reason", statusCode = status)
                }
            } catch (e: SocketTimeoutException) {
                Log.w(tag, "$method $path timed out against $endpoint", e)
                TransferResult.Failure(
                    "No answer from the desktop at $endpoint. Check the address, and that " +
                        "the desktop is on the same Wi-Fi and has sharing switched on.",
                )
            } catch (e: IOException) {
                // Logged, because this is the branch that swallowed a cleartext refusal whole: the
                // platform throws here before any socket is opened, the desktop never sees the
                // request, and the operator gets a message about the network for a problem that
                // had nothing to do with it. Naming the cause here is what makes that class of
                // failure findable at all.
                Log.w(tag, "$method $path failed to reach $endpoint", e)
                TransferResult.Failure(
                    "Could not reach the desktop at $endpoint. Check the address, the network " +
                        "cable, and that the desktop has sharing switched on.",
                )
            } catch (e: Exception) {
                // Last-resort net so a transfer can never take the screen down. The class name is
                // logged for us and kept out of the operator's message.
                Log.e(tag, "$method $path failed unexpectedly", e)
                TransferResult.Failure("The transfer could not be completed.")
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Reads whichever stream carries the payload. A non-2xx puts the reason on `errorStream`, so
     * reading only `inputStream` would throw away the single most informative part of the reply.
     */
    private fun readPayload(connection: HttpURLConnection, status: Int): String {
        val stream: InputStream? =
            if (status in 200..299) connection.inputStream else connection.errorStream

        // The hub caps inbound documents, but a response can still be large; read the stream
        // rather than assuming the whole document fits a buffer's worth of bytes.
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun describeOk(path: String): String = when (path) {
        "/health" -> "Desktop is sharing."
        "/push" -> "Sent to the desktop."
        else -> "Received from the desktop."
    }

    private fun defaultStatusText(status: Int): String = when (status) {
        in 400..499 -> "the desktop rejected the request (HTTP $status)"
        in 500..599 -> "the desktop had a problem (HTTP $status)"
        else -> "HTTP $status"
    }

    companion object {
        /** Fixed by the wire contract; both sides read it from the plan, not from config. */
        const val DEFAULT_PORT = 8756
        const val DEFAULT_TIMEOUT_MS = 10_000

        /**
         * Normalises an operator-typed address, or returns null if nothing usable is left.
         *
         * The desktop puts a complete `http://192.168.1.50:8756` on screen for the operator to
         * read off, and the single most likely mistake is to type or scan that whole string into
         * a field that wants only the IP. Stripping the scheme, a trailing path and a trailing
         * colon turns the common mistake into a working transfer instead of an error the operator
         * cannot diagnose. A bare host, an IP, or a hostname all pass through untouched.
         *
         * Order matters here: the scheme has to come off *before* the path is split, otherwise
         * `http://host` truncates at the `//` and leaves the bare word `http`.
         */
        internal fun sanitizeHost(raw: String): String? =
            raw.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .trim()
                .let { withoutScheme ->
                    withoutScheme
                        .substringBefore('/')
                        .substringBefore('?')
                        .substringBefore('#')
                        .trim()
                }
                // The port is fixed by the wire contract and is supplied separately below, so a
                // port in the typed text is always redundant. Leaving it on would produce
                // "http://192.168.1.50:8756:8756" and a failure nobody could explain.
                .replace(TRAILING_PORT, "")
                .trim()
                .takeIf { it.isNotEmpty() && it.none { char -> char.isWhitespace() } }

        /** A trailing `:<digits>` - the port the desktop happens to display, which we already know. */
        private val TRAILING_PORT = Regex(":\\d+$")
    }
}
