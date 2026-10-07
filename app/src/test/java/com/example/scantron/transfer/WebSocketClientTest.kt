package com.example.scantron.transfer

import com.example.scantron.sync.SyncJson
import com.example.scantron.sync.SyncMessage
import com.example.scantron.sync.SyncMessageType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * [WebSocketClient] over a real socket, against a server that implements the spec independently.
 *
 * The unit tests cover the framing rules; these cover the things only a socket can fail at - the
 * handshake actually completing, the port actually being the one asked for, and a second frame being
 * read after the first.
 */
@RunWith(RobolectricTestRunner::class)
class WebSocketClientTest {

    @Test
    fun `the handshake completes and both directions carry a frame`() {
        // Regression: `connect(InetSocketAddress(host, port))` written inside `Socket().apply { }`
        // silently resolves `port` to Socket.getPort() - zero before connect - so the client dialled
        // port 0 and never reached anything. Only a test that asserts a connection *happens* catches
        // that, because the failure is a clean IOException with a plausible message.
        val receivedByServer = AtomicReference<String>()
        val receivedByClient = mutableListOf<String>()

        val server = FakeDesktopServer { conversation ->
            receivedByServer.set(conversation.readAnyText())
            conversation.send(SyncMessage(type = SyncMessageType.Ping))
            Thread.sleep(300)
            conversation.sayGoodbye("done")
        }

        server.use {
            val client = WebSocketClient("127.0.0.1", server.port, readTimeoutMs = 2_000)
            val outcome = runBlocking {
                client.connectAndPump(
                    onOpen = { send -> send.sendText(SyncJson.write(SyncMessage(type = SyncMessageType.Hello))) },
                    onText = { receivedByClient += it; true },
                )
            }

            assertEquals("the handshake never completed", 1, server.connections.get())

            // The port the client dialled must be the port the client was constructed with.
            assertEquals(
                SyncMessageType.Hello,
                (SyncJson.read(receivedByServer.get()) as SyncJson.ReadResult.Parsed).message.type,
            )

            // Every text frame reaches the caller in order, including the control messages: the
            // engine, not the transport, decides what a ping or a bye means.
            assertEquals(
                listOf(SyncMessageType.Ping, SyncMessageType.Bye),
                receivedByClient.map { (SyncJson.read(it) as SyncJson.ReadResult.Parsed).message.type },
            )

            // A close frame carrying a reason ends the session cleanly with that reason.
            assertEquals("done", (outcome as SyncResult.Closed).reason)
        }
    }

    @Test
    fun `a peer that is not a websocket server is refused in words`() {
        val plain = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

        Thread({
            try {
                plain.accept().use { socket ->
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                    Thread.sleep(200)
                }
            } catch (e: Exception) {
                // The test closes the server as soon as it has its answer.
            }
        }, "not-a-websocket").apply { isDaemon = true }.start()

        try {
            val client = WebSocketClient("127.0.0.1", plain.localPort, readTimeoutMs = 2_000)
            val outcome = runBlocking {
                client.connectAndPump(onOpen = { }, onText = { true })
            }

            assertTrue(outcome is SyncResult.Failure)

            // There is no TLS on this link, so the accept digest is the only evidence the peer is a
            // WebSocket server; a wrong one has to be refused rather than decoded as frames.
            val message = outcome.message
            assertTrue(message, message.contains("not speaking Scantron sync"))
            assertTrue(message, !message.contains("Exception"))
        } finally {
            plain.close()
        }
    }

    @Test
    fun `a peer that goes away mid-session is reported without an exception name`() {
        val server = FakeDesktopServer { conversation ->
            conversation.readAnyText()
            conversation.disconnect()
        }

        server.use {
            val client = WebSocketClient("127.0.0.1", server.port, readTimeoutMs = 2_000)
            val outcome = runBlocking {
                client.connectAndPump(
                    onOpen = { send -> send.sendText(SyncJson.write(SyncMessage(type = SyncMessageType.Hello))) },
                    onText = { true },
                )
            }

            assertTrue(outcome is SyncResult.Failure)
            assertTrue(outcome.message, !outcome.message.contains("Exception"))
            assertTrue(outcome.message, !outcome.message.contains("java."))
        }
    }

    @Test
    fun `an unreachable port is reported in words rather than thrown`() {
        // Bound and immediately closed, so the port is almost certainly free but was real.
        val free = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = free.localPort
        free.close()

        val client = WebSocketClient("127.0.0.1", port, connectTimeoutMs = 1_000)
        val outcome = runBlocking { client.connectAndPump(onOpen = { }, onText = { true }) }

        assertTrue(outcome is SyncResult.Failure)
        assertTrue(outcome.message.contains("Could not reach"))
    }

    @Test
    fun `within one read, two frames are both delivered`() {
        // Regression: the frame loop used to discard the bytes left over after one decode. A read can
        // legitimately return two small frames at once, and the leftover was then thrown away - so the
        // *second* frame of every pair was lost, and the next decode indexed past the end of an empty
        // buffer.
        val server = FakeDesktopServer { conversation ->
            conversation.readAnyText()
            // Two frames written back to back, which the OS will usually deliver in one read.
            conversation.sendRaw(SyncJson.write(SyncMessage(type = SyncMessageType.Ping)))
            conversation.sendRaw(SyncJson.write(SyncMessage(type = SyncMessageType.Pong)))
            Thread.sleep(400)
            conversation.sayGoodbye("done")
        }

        server.use {
            val client = WebSocketClient("127.0.0.1", server.port, readTimeoutMs = 2_000)
            val seen = mutableListOf<String>()

            runBlocking {
                client.connectAndPump(
                    onOpen = { send -> send.sendText(SyncJson.write(SyncMessage(type = SyncMessageType.Hello))) },
                    onText = { text ->
                        seen += (SyncJson.read(text) as SyncJson.ReadResult.Parsed).message.type.name
                        true
                    },
                )
            }

            // Both frames must arrive, in order. The regression this guards is the *second* frame of
            // a pair being dropped because the bytes left over after the first decode were discarded.
            assertEquals(listOf("Ping", "Pong", "Bye"), seen)
        }
    }
}
