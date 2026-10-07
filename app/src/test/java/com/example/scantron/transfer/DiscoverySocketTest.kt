package com.example.scantron.transfer

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.SocketException

/**
 * [Discovery] driven over a real socket against [FakeDesktopResponder].
 *
 * The regression these exist for: the probe socket was opened without `SO_BROADCAST`, so a send to a
 * broadcast address was rejected by the kernel and then swallowed by a `runCatching` - discovery
 * could never find anything and said nothing about it. A loopback target is injected because a test
 * host has no real subnet to compute a broadcast address for; what these pin is the
 * send / receive / parse plumbing that the broadcast path shares.
 *
 * Plain JUnit with Robolectric only for `android.util.Log` and `WifiManager`, mirroring
 * [TransferListenerTest]. No device is needed.
 */
@RunWith(RobolectricTestRunner::class)
class DiscoverySocketTest {

    @Test
    fun `a desktop that answers the probe is surfaced as a peer`() = runBlocking {
        val responder = startResponderOrSkip()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val discovery = loopbackDiscovery()

        try {
            discovery.start(scope)

            val peer = awaitPeer(discovery)
            assertNotNull("the fake desktop should have been discovered", peer)
            assertEquals("DESKTOP-TEST", peer?.name)
            assertEquals("127.0.0.1", peer?.host)
            assertEquals(8756, peer?.port ?: -1)
        } finally {
            discovery.stop()
            scope.cancel()
            responder.stop()
        }
    }

    @Test
    fun `no responder means an empty list rather than a crash`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val discovery = loopbackDiscovery()

        try {
            discovery.start(scope)
            delay(1_500)
            assertTrue(discovery.peers.value.isEmpty())
        } finally {
            discovery.stop()
            scope.cancel()
        }
    }

    private fun loopbackDiscovery(): Discovery = Discovery(
        context = ApplicationProvider.getApplicationContext(),
        probeTargets = { listOf(InetAddress.getByName("127.0.0.1")) },
    )

    /** Skips rather than fails when the fixed discovery port is already taken in this environment. */
    private fun startResponderOrSkip(): FakeDesktopResponder {
        val responder = FakeDesktopResponder()
        try {
            responder.start()
        } catch (e: SocketException) {
            Assume.assumeNoException("UDP ${Discovery.PORT} is not available here", e)
        }
        return responder
    }

    private suspend fun awaitPeer(discovery: Discovery, timeoutMs: Long = 8_000): Peer? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            discovery.peers.value.firstOrNull()?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
