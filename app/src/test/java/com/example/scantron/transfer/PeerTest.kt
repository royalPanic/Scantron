package com.example.scantron.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The discovery reply parser, and the staleness rule that keeps dead desktops off the list.
 *
 * These are the parts of Phase 2 that can be tested without a socket. The socket itself is
 * untestable off-device by nature - a CK65 on a warehouse Wi-Fi is the only place it can be
 * proven - so the parsing contract between the two halves is pinned here instead, where a change
 * to either side will be caught immediately.
 */
class PeerTest {

    @Test
    fun `a well formed reply becomes a peer`() {
        val peer = Peer.parse("SCANTRON_HUB/1 DESKTOP-01 192.168.1.50 8756", nowMillis = 1_000L)

        assertEquals(Peer("DESKTOP-01", "192.168.1.50", 8756, 1_000L), peer)
    }

    @Test
    fun `surrounding and repeated whitespace is tolerated`() {
        // The desktop is a separate codebase; it may well pad the line. Being strict here would
        // make discovery fail over whitespace rather than over anything meaningful.
        val peer = Peer.parse("  SCANTRON_HUB/1   DESKTOP-01   192.168.1.50   8756  ", nowMillis = 5L)

        assertEquals(Peer("DESKTOP-01", "192.168.1.50", 8756, 5L), peer)
    }

    @Test
    fun `a reply from some other program on the network is ignored`() {
        // Broadcast reaches every host on the subnet, so unrelated UDP traffic will be seen.
        assertNull(Peer.parse("NOTIFY/1 printer 192.168.1.9 9100"))
        assertNull(Peer.parse("hello"))
    }

    @Test
    fun `a truncated or malformed reply is ignored rather than throwing`() {
        assertNull(Peer.parse("SCANTRON_HUB/1"))
        assertNull(Peer.parse("SCANTRON_HUB/1 DESKTOP-01 192.168.1.50"))
        // A non-numeric port is the classic version-skew symptom and must not be rendered as a
        // row the operator could tap and then fail to connect to.
        assertNull(Peer.parse("SCANTRON_HUB/1 DESKTOP-01 192.168.1.50 not-a-port"))
    }

    @Test
    fun `a peer that has gone quiet is pruned`() {
        val now = 100_000L
        val fresh = Peer("FRESH", "192.168.1.50", 8756, now - 1_000L)
        val stale = Peer("STALE", "192.168.1.51", 8756, now - Peer.STALE_AFTER_MS - 1L)

        val kept = listOf(fresh, stale).pruneStale(nowMillis = now)

        assertEquals(listOf(fresh), kept)
    }

    @Test
    fun `a peer inside the stale window survives`() {
        // One dropped broadcast in a noisy warehouse must not make a row blink out from under
        // the operator, so the window allows several missed probes.
        val now = 100_000L
        val peer = Peer("HUB", "192.168.1.50", 8756, now - Peer.STALE_AFTER_MS + 1L)

        assertTrue(listOf(peer).pruneStale(nowMillis = now).isNotEmpty())
    }
}