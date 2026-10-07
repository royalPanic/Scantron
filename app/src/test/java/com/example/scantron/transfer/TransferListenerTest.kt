package com.example.scantron.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [TransferListener] driven by a real desktop-style push over a real socket.
 *
 * The regression these exist for: the desktop's "Send to handheld" button used to send nothing at
 * all, so nothing on this side had to answer. Every case therefore asserts on the reply a desktop
 * would actually receive - the status code and the body it shows the operator - rather than on
 * internal state that a real push would never reach.
 *
 * Plain JUnit with Robolectric only for `android.util.Log`, mirroring [TransferClientTest]. The
 * listener touches no framework type beyond that, so the socket and the HTTP contract can be
 * exercised on the JVM with no device attached.
 */
@RunWith(RobolectricTestRunner::class)
class TransferListenerTest {

    /** A minimal export document, as the desktop would serialize it. */
    private fun document(containers: Int = 1, itemsPerContainer: Int = 1): String {
        val containersJson = (0 until containers).joinToString(",") { index ->
            val items = (0 until itemsPerContainer).joinToString(",") { item ->
                """{"uuid":"u$index-$item","name":"Drill","quantity":4,"updatedAt":1700000000000}"""
            }
            """{"id":"BOX-$index","name":"Shelf","items":[$items]}"""
        }

        return """{"version":1,"exportedAt":"2026-01-01T00:00:00Z","containers":[$containersJson]}"""
    }

    /** Posts a body the way the desktop does, and reports the status and body it got back. */
    private fun push(
        port: Int,
        body: String,
        method: String = "POST",
        path: String = TransferListener.PATH,
    ): Pair<Int, String> {
        val connection = URL("http", "127.0.0.1", port, path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val payload = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            status to payload
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a push is accepted, counted, and staged rather than imported`() = runBlocking {
        val staged = ArrayBlockingQueue<StagedPush>(1)
        val listener = TransferListener(port = 0).apply { onPushReceived = { staged.offer(it) } }

        assertNull(listener.start())
        val port = listener.boundPort

        try {
            val (status, body) = push(port, document(containers = 2, itemsPerContainer = 3))

            // Accepted - and explicitly marked as staged, so a desktop can tell the operator the
            // device still needs their confirmation.
            assertEquals(200, status)
            assertTrue(body, body.contains("\"containers\":2"))
            assertTrue(body, body.contains("\"items\":6"))
            assertTrue(body, body.contains("\"staged\":true"))

            // The document reached the staging callback with the counts the dialog will show.
            val received = staged.poll(10, TimeUnit.SECONDS)
            assertNotNull("The listener never handed the push on", received)
            assertEquals(2, received!!.containerCount)
            assertEquals(6, received.itemCount)

            // Byte-identical to what was sent, so the two routes stay interchangeable.
            assertEquals(document(containers = 2, itemsPerContainer = 3), received.document)
        } finally {
            listener.stop()
        }
    }

    @Test
    fun `a document this device cannot read is refused and changes nothing`() = runBlocking {
        val staged = ArrayBlockingQueue<StagedPush>(1)
        val listener = TransferListener(port = 0).apply { onPushReceived = { staged.offer(it) } }

        assertNull(listener.start())
        val port = listener.boundPort

        try {
            val (status, body) = push(port, """{"not":"an inventory"}""")

            // 400 with a sentence, because the desktop shows this body to the operator verbatim.
            assertEquals(400, status)
            assertTrue(body, body.contains("not a Scantron document"))

            // Nothing staged: a document that cannot be read must not become a staged one.
            assertNull(staged.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            listener.stop()
        }
    }

    @Test
    fun `an empty body is refused rather than treated as an empty inventory`() = runBlocking {
        val staged = ArrayBlockingQueue<StagedPush>(1)
        val listener = TransferListener(port = 0).apply { onPushReceived = { staged.offer(it) } }

        assertNull(listener.start())
        val port = listener.boundPort

        try {
            val (status, body) = push(port, "")

            assertEquals(400, status)
            assertTrue(body, body.contains("empty document"))
            assertNull(staged.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            listener.stop()
        }
    }

    @Test
    fun `the wrong route is a 404 the desktop can read`() = runBlocking {
        val listener = TransferListener(port = 0)

        assertNull(listener.start())
        val port = listener.boundPort

        try {
            // A GET, as the old pull contract used: the device no longer serves pulls, and it has
            // to say so rather than accepting a document on a route that means something else.
            val (status, body) = push(port, document(), method = "GET", path = "/pull")

            assertEquals(404, status)
            assertTrue(body, body.contains(TransferListener.PATH))
        } finally {
            listener.stop()
        }
    }

    @Test
    fun `a bind that is already taken is reported rather than thrown`() {
        val first = TransferListener(port = 0)
        assertNull(first.start())
        val port = first.boundPort

        try {
            // A second listener on the same port is the "another copy is open" case, which has to
            // arrive as a message the screen can show rather than as an exception.
            val second = TransferListener(port = port)
            val failure = second.start()

            assertNotNull("A refused bind must be reported", failure)
            assertTrue(failure!!, failure.contains(port.toString()))
            assertNotNull(second.problem)
            assertFalse(second.isListening)
        } finally {
            first.stop()
        }
    }

    @Test
    fun `stopping releases the port so it can be rebound`() {
        val first = TransferListener(port = 0)
        assertNull(first.start())
        val port = first.boundPort
        first.stop()

        assertFalse(first.isListening)

        // Rebinding the same port is what proves the socket was really closed, rather than left to
        // a finalizer - a leaked socket would make the desktop's next push fail mysteriously.
        val second = TransferListener(port = port)
        assertNull(second.start())
        assertTrue(second.isListening)
        second.stop()
    }

    @Test
    fun `the listener port is distinct from the hub and discovery ports`() {
        // All three sockets can be open at once, so a collision would mean the desktop could not
        // serve a pull while also pushing to the device.
        assertTrue(TransferListener.DEFAULT_PORT != 8756)
        assertTrue(TransferListener.DEFAULT_PORT != 8757)
    }
}