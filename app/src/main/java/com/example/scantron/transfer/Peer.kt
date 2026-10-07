package com.example.scantron.transfer

/**
 * A desktop hub that answered a discovery probe.
 *
 * A plain value type so the discovery list is diffable by Compose and so the parser can be tested
 * without a socket. Identity is host+port rather than name: two warehouse desktops could
 * plausibly both be called "DESKTOPS", and collapsing them into one row would leave the operator
 * unable to tell which one they are about to overwrite.
 */
data class Peer(
    /** Machine name as reported by the desktop, for display. */
    val name: String,
    val host: String,
    val port: Int,
    /** When this peer last answered, on the device's clock. */
    val lastSeenMillis: Long,
) {
    companion object {
        const val PREFIX = "SCANTRON_HUB/1"

        /**
         * A peer silent for this long is presumed gone. Sized to allow several missed probes
         * before a desktop disappears - a single dropped broadcast in a noisy warehouse must not
         * make the row blink out from under the operator.
         */
        const val STALE_AFTER_MS = 15_000L

        /**
         * Parses the desktop's reply to a `WHO_HAS` probe:
         *
         * ```text
         * SCANTRON_HUB/1 <name> <host> <port>
         * ```
         *
         * Returns null for anything malformed rather than throwing. The peer that answers is a
         * machine on the same broadcast domain running a build we do not control, so a surprising
         * packet must never be able to take the discovery list down.
         */
        fun parse(reply: String, nowMillis: Long = System.currentTimeMillis()): Peer? {
            val fields = reply.trim().split(Regex("\\s+"))
            if (fields.size < 4) return null
            if (fields[0] != PREFIX) return null

            val name = fields[1]
            val host = fields[2]
            val port = fields[3].toIntOrNull() ?: return null

            // Guard against fields that are present but empty after splitting; a blank row the
            // operator cannot identify is worse than ignoring the packet.
            if (name.isEmpty() || host.isEmpty()) return null

            return Peer(name = name, host = host, port = port, lastSeenMillis = nowMillis)
        }
    }
}

/**
 * Drops peers that have stopped answering.
 *
 * Without this the list only ever grows and keeps offering desktops whose sharing has been
 * switched off, which is the worst possible outcome: the operator picks a row that then cannot
 * connect.
 */
fun List<Peer>.pruneStale(
    nowMillis: Long = System.currentTimeMillis(),
    staleAfterMs: Long = Peer.STALE_AFTER_MS,
): List<Peer> = filter { nowMillis - it.lastSeenMillis <= staleAfterMs }