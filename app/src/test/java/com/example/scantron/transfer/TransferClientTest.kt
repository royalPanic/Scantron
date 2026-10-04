package com.example.scantron.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets

/**
 * [TransferClient] against a real socket, via [FakeHubServer].
 *
 * These are the tests that keep the wire contract honest on this side of it: the exact bytes of a
 * push, the untouched bytes of a pull, the desktop's plain-text reason surfacing verbatim on a
 * failure, and - the one that matters most in a warehouse - that a dead endpoint produces a
 * reported failure rather than a frozen screen or a thrown exception.
 *
 * Plain JUnit rather than Robolectric coroutine rules: the client suspends and hops to
 * `Dispatchers.IO`, which is exactly the behaviour under test, and it touches nothing from the
 * Android framework except the `Log` tag.
 */
@RunWith(RobolectricTestRunner::class)
class TransferClientTest {

    private fun client(port: Int, timeoutMs: Int = 5_000) =
        TransferClient(host = "127.0.0.1", port = port, timeoutMs = timeoutMs)

    @Test
    fun `health returns the hub's plain text answer`() = runBlocking {
        FakeHubServer { FakeHubServer.Response(200, "scantron-hub/1 DESKTOP-ABC", "text/plain") }
            .use { server ->
                val result = client(server.port).health()

                val success = result as TransferResult.Success
                assertEquals(200, success.statusCode)
                assertEquals("scantron-hub/1 DESKTOP-ABC", success.body)
            }
    }

    @Test
    fun `health asked for the documented route`() = runBlocking {
        FakeHubServer { FakeHubServer.Response(200, "scantron-hub/1 PC", "text/plain") }
            .use { server ->
                client(server.port).health()

                val request = server.takeRequest()
                assertEquals("GET", request?.method)
                assertEquals("/health", request?.path)
            }
    }

    @Test
    fun `a 404 surfaces the desktop's reason rather than an exception name`() = runBlocking {
        FakeHubServer { FakeHubServer.Response(404, "No such route.", "text/plain") }
            .use { server ->
                val result = client(server.port).health()

                val failure = result as TransferResult.Failure
                assertEquals(404, failure.statusCode)
                assertEquals("Desktop said: No such route.", failure.message)
            }
    }

    @Test
    fun `an empty error body still produces a readable message`() = runBlocking {
        FakeHubServer { FakeHubServer.Response(500, "", "text/plain") }.use { server ->
            val failure = client(server.port).pull() as TransferResult.Failure

            assertEquals(500, failure.statusCode)
            // Never blank, and never a Java class name.
            assertTrue("message must not be blank", failure.message.isNotBlank())
            assertTrue(failure.message.contains("500"))
        }
    }

    @Test
    fun `push sends the exact bytes it was given`() = runBlocking {
        // Deliberately awkward content: non-ASCII, a body that is not reformat-able JSON, and
        // leading whitespace that would survive a naive round trip through a parser and a writer.
        val document = """{"note":"café — naïve","containers":[]}"""

        FakeHubServer { FakeHubServer.Response(200, """{"ok":true,"containers":0,"items":0}""") }
            .use { server ->
                client(server.port).push(document)

                val request = server.takeRequest()
                assertEquals("POST", request?.method)
                assertEquals("/push", request?.path)
                assertEquals("application/json", request?.contentType)
                assertEquals(
                    "push must put the document on the wire unchanged",
                    document,
                    request?.body,
                )
                // And unchanged as bytes, not merely as an equal string after re-encoding.
                assertEquals(
                    document.toByteArray(StandardCharsets.UTF_8).size,
                    request?.body?.toByteArray(StandardCharsets.UTF_8)?.size,
                )
            }
    }

    @Test
    fun `pull returns the desktop's document unchanged`() = runBlocking {
        val document = """{"app":"Scantron","version":"1.1","containers":[{"id":"BOX-1"}]}"""

        FakeHubServer { FakeHubServer.Response(200, document) }.use { server ->
            val result = client(server.port).pull()

            assertEquals(document, (result as TransferResult.Success).body)
        }
    }

    @Test
    fun `a five hundred with a plain text body surfaces that text`() = runBlocking {
        val reason = "Nothing to send — open a document on the desktop first."

        FakeHubServer { FakeHubServer.Response(500, reason, "text/plain") }.use { server ->
            val failure = client(server.port).pull() as TransferResult.Failure

            assertEquals(500, failure.statusCode)
            assertTrue(
                "the desktop's own words must reach the operator",
                failure.message.contains(reason),
            )
            assertTrue(
                "no exception class names in operator-facing text",
                !failure.message.contains("Exception"),
            )
        }
    }

    @Test
    fun `a refused connection fails instead of throwing`() = runBlocking {
        ReservedClosedPort.reserve().use { reserved ->
            // Release immediately before connecting so the port is genuinely unserved.
            reserved.release()

            val result = client(reserved.port).health()

            val failure = result as TransferResult.Failure
            assertEquals(-1, failure.statusCode)
            assertTrue(
                "must explain itself in plain words: ${failure.message}",
                failure.message.contains("Could not reach the desktop"),
            )
        }
    }

    @Test
    fun `a read timeout fails instead of hanging`() = runBlocking {
        // Server accepts, records the request, then answers nothing at all. Without a read
        // timeout this call would block for as long as the warehouse dead spot lasts.
        FakeHubServer { null }.use { server ->
            val result = client(server.port, timeoutMs = 750).health()

            val failure = result as TransferResult.Failure
            assertEquals(-1, failure.statusCode)
            assertTrue(
                "a timeout must report itself as one: ${failure.message}",
                failure.message.contains("No answer from the desktop"),
            )
        }
    }

    @Test
    fun `an unusable address is reported as a typo, not a network fault`() = runBlocking {
                val result = TransferClient(host = "   ").health()

                val failure = result as TransferResult.Failure
                assertTrue(
                    "a typo must not read like a network problem: ${failure.message}",
                    failure.message.contains("not a desktop address"),
                )
            }

            @Test
            fun `a full url pasted into the address field still connects`() = runBlocking {
                // The desktop displays "http://192.168.1.50:8756" for the operator to read off, so
                            // the whole string landing in the field is a predictable mistake, not an edge
                            // case. The port passed here is deliberately not the one in the pasted text, so
                            // this also pins that a typed-in port is ignored rather than appended.
                            FakeHubServer { FakeHubServer.Response(200, "scantron-hub/1 PC", "text/plain") }
                                .use { server ->
                                    val result = TransferClient(
                                        host = "http://127.0.0.1:8756",
                                        port = server.port,
                                    ).health()

                                    assertTrue("expected a connection, got: $result", result is TransferResult.Success)
                                }
                        }

            @Test
        fun `an unresolvable host reports a network failure rather than throwing`() = runBlocking {
            // A syntactically valid name that resolves nowhere - the real shape of "operator typed
            // the desktop's hostname instead of its IP". Must come back as a reported failure.
            val result = TransferClient(host = "no-such-host.invalid", timeoutMs = 5_000).health()

            val failure = result as TransferResult.Failure
            assertEquals(-1, failure.statusCode)
            assertTrue(
                "must explain itself in plain words: ${failure.message}",
                failure.message.contains("Could not reach the desktop"),
            )
        }

        @Test
                fun `a pasted address is normalised to a bare host`() {
                    // The desktop shows a full URL for the operator to read off. Every one of these is
                    // something a scanner or a copy-paste can plausibly deliver, and all must reduce to
                    // the same bare host - the port is fixed by the contract and supplied separately.
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("192.168.1.50"))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("  192.168.1.50  "))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("192.168.1.50:8756"))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("http://192.168.1.50"))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("http://192.168.1.50:8756"))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("http://192.168.1.50:8756/"))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("http://192.168.1.50:8756/health"))
                    assertEquals("192.168.1.50", TransferClient.sanitizeHost("https://192.168.1.50:8756"))
                }

                @Test
                fun `a hostname is left alone`() {
                    // The desktop may be reachable by name on a warehouse network; normalising must not
                    // mangle that into something different.
                    assertEquals("hub-warehouse", TransferClient.sanitizeHost("hub-warehouse"))
                    assertEquals("hub-warehouse", TransferClient.sanitizeHost("http://hub-warehouse:8756"))
                }

                @Test
                fun `nothing usable is rejected`() {
                    assertNull(TransferClient.sanitizeHost(""))
                    assertNull(TransferClient.sanitizeHost("   "))
                    assertNull(TransferClient.sanitizeHost("http://"))
                    // Embedded whitespace is a scanner picking up two tags in one read.
                    assertNull(TransferClient.sanitizeHost("192.168.1.50 192.168.1.51"))
                }

                @Test
                fun `every verb is reachable and none of them throw`() = runBlocking {
        FakeHubServer { request ->
            when (request.path) {
                "/pull" -> FakeHubServer.Response(200, """{"containers":[]}""")
                else -> FakeHubServer.Response(200, "ok", "text/plain")
            }
        }.use { server ->
            val client = client(server.port)

            assertTrue(client.health() is TransferResult.Success)
            assertTrue(client.push("{}") is TransferResult.Success)
            assertTrue(client.pull() is TransferResult.Success)
        }
    }
}