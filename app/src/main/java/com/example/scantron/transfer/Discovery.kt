package com.example.scantron.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Finds desktops on the local network by UDP broadcast. Phase 2, and entirely optional.
 *
 * The operator can always type an address by hand, which is the supported way to transfer. This
 * exists to remove the typing, and it is built so that every way it can fail leaves that manual
 * path working and reachable. That is the whole design constraint, and it is why this class
 * reports a reason rather than failing silently and why the screen degrades to "type it in".
 *
 * Protocol, matching the desktop's half:
 *  - the device broadcasts `WHO_HAS` to UDP [PORT] about every [PROBE_INTERVAL_MS] while the
 *    screen is open;
 *  - each desktop with sharing switched on replies unicast with
 *    `SCANTRON_HUB/1 <name> <host> <port>`.
 *
 * Broadcast rather than multicast, deliberately: a broadcast address reaches a desktop on the
 * subnet with no group membership to join, which is one less thing to get wrong across the many
 * Wi-Fi access points a warehouse runs. What still needs care is the platform's multicast lock -
 * Android silently discards broadcast traffic from a `DatagramSocket` unless the app holds a
 * [WifiManager.MulticastLock] and declares `CHANGE_WIFI_MULTICAST_STATE`, and it does so without
 * any error.
 */
class Discovery(private val context: Context) {

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

        /** Non-null when the last search attempt could not run; shown as guidance, not as an error. */
        private val _problem = MutableStateFlow<String?>(null)
        val problem: StateFlow<String?> = _problem.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Starts probing. Any previous search is stopped first, so a double tap cannot leave two
     * sockets bound to the same port - the second would fail to bind and take the first one
     * down with it when the test/loop unwinds.
     */
    fun start(scope: CoroutineScope) {
        stop()
        this.scope = scope
        _isSearching.value = true
                _problem.value = null

        scope.launch(Dispatchers.IO) {
            acquireMulticastLock()

                    // An unbound socket takes an ephemeral port of the OS's choosing. That matters: the
                    // desktop replies to wherever the probe came *from*, so binding to DISCOVERY_PORT
                    // would stop replies arriving, and the desktop never learns our port otherwise.
                    //
                    // soTimeout is what ends each probe window - receive() is the "wait for replies" step.
                    // A blocked receive would otherwise hold the port open indefinitely if the ViewModel
                    // were cleared mid-window.
                    val socket = runCatching {
                        DatagramSocket().apply { soTimeout = RECEIVE_POLL_TIMEOUT_MS }
                    }.getOrElse { failure ->
                        Log.w(TAG, "Could not open a discovery socket", failure)
                        _isSearching.value = false
                        reportProblem("Could not search the network. Type the desktop address instead.")
                        return@launch
                    }
                    this@Discovery.socket = socket

                    val buffer = ByteArray(MAX_REPLY_BYTES)
                    val probe = WHO_HAS.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(buffer, buffer.size)

                    while (isActive) {
                        broadcastProbe(socket, probe)

                        // A few reads per window rather than a tight drain loop: each desktop replies
                        // within milliseconds of the broadcast, so more than this just burns battery
                        // waiting for duplicates that will not arrive.
                        repeat(RECEIVES_PER_WINDOW) {
                                                    // receive() returns void and reports a timeout by throwing, so
                                                    // arrival is detected by whether the call completed - not by a
                                                    // return value. packet.length is set to the bytes actually read.
                                                    val arrived = runCatching { socket.receive(packet) }.isSuccess
                                                    if (arrived && packet.length > 0) {
                                                        recordReply(String(buffer, 0, packet.length, Charsets.UTF_8))
                                                    }
                                                }

                        delay(PROBE_INTERVAL_MS)
                    }
                }
            }

    /** Stops probing and releases the socket and the multicast lock. Safe to call when idle. */
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        scope = null
        _isSearching.value = false
    }

    /**
         * Replaces the peer at the same address with the newer one.
     *
         * Last-seen is carried on the peer rather than tracked separately because a desktop that
         * stops answering has to visibly go stale - otherwise the list fills with machines whose
         * sharing has been switched off and the operator picks one that cannot connect.
     */
    private fun recordReply(reply: String) {
        val peer = Peer.parse(reply) ?: run {
            Log.d(TAG, "Ignoring unrecognised discovery reply: $reply")
            return
        }
        Log.i(TAG, "Found hub ${peer.name} at ${peer.host}:${peer.port}")

        _peers.value = (_peers.value.filterNot { it.host == peer.host && it.port == peer.port } + peer)
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Sends `WHO_HAS` to the broadcast address of every up, non-loopback IPv4 interface.
     *
     * The directed broadcast of the device's own subnet is used rather than the global
     * `255.255.255.255`, because routers drop the latter and many warehouse APs do too.
     */
    private suspend fun broadcastProbe(socket: DatagramSocket, probe: ByteArray) =
        withContext(Dispatchers.IO) {
            val targets = broadcastAddresses()
            if (targets.isEmpty()) {
                Log.d(TAG, "No usable interface for a discovery broadcast")
                return@withContext
            }

            targets.forEach { address ->
                runCatching {
                    socket.send(
                        DatagramPacket(probe, probe.size, address, PORT),
                    )
                }.onFailure { Log.w(TAG, "Discovery broadcast to $address failed", it) }
            }
        }

    /**
     * The directed broadcast address for each usable interface.
     *
         * Computed from the interface address and its netmask rather than hardcoded to `x.y.z.255`,
         * because a warehouse AP may hand out a /22 or a /20 and a fixed /24 assumption would send
         * the probe nowhere on a real deployment.
     */
    private fun broadcastAddresses(): List<InetAddress> {
            val interfaces: List<NetworkInterface> =
            NetworkInterface.getNetworkInterfaces().toList()

            return interfaces
                .filter { interfaceUp(it) }
                .flatMap { networkInterface ->
                    val addresses: List<InetAddress> = networkInterface.inetAddresses.toList()
                    addresses.mapNotNull { address ->
                        val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                        if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) return@mapNotNull null
                        directedBroadcast(ipv4, prefixLengthOf(networkInterface, address))
                    }
                }
        }

        private fun interfaceUp(networkInterface: NetworkInterface): Boolean =
            runCatching { networkInterface.isUp && !networkInterface.isLoopback }
                .getOrDefault(false)

        /**
         * The subnet prefix length the interface reports for [address], or a conservative default.
         *
         * A wrong guess only costs a missed discovery window; a crash here would cost the feature.
         */
        private fun prefixLengthOf(networkInterface: NetworkInterface, address: Inet4Address): Int =
            runCatching {
                networkInterface.interfaceAddresses
                    .firstOrNull { it.address == address }
                    ?.networkPrefixLength
                    ?.toInt()
            }.getOrNull() ?: DEFAULT_PREFIX_LENGTH

                                            /**
                                             * Sets the host portion of [address] to ones for the given prefix length.
                                             *
                                             * Computed rather than read from `Inet4Address.getBroadcastAddress`, which the Android
                                             * platform API does not expose for a bare address. A /32 - a single host with no network -
                                             * has no meaningful broadcast, so null is returned and the interface is skipped rather than
                                             * broadcasting the host's own address back at itself.
                                             */
                                            private fun directedBroadcast(address: Inet4Address, prefixLength: Int): InetAddress? {
                                                if (prefixLength < 0 || prefixLength >= 32) return null

                                                val value = address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
                                                val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
                                                val broadcast = ((value and mask) or mask.inv()) and 0xFFFFFFFFL

                                                return runCatching {
                                                    InetAddress.getByAddress(
                                                        byteArrayOf(
                                                            (broadcast ushr 24).toByte(),
                                                            (broadcast ushr 16).toByte(),
                                                            (broadcast ushr 8).toByte(),
                                                            broadcast.toByte(),
                                                        ),
                                                    )
                                                }.getOrNull()
                                            }

    /**
     * Holds the Wi-Fi multicast lock for as long as the search runs.
     *
     * This is the single most common reason discovery silently finds nothing: without it the
     * packets are dropped by the Wi-Fi driver with no error reported anywhere. It is acquired
     * per-interval rather than held forever because the platform counts a held lock against the
     * app's background power budget and the warehouse battery is not infinite.
     */
    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
         * Records why a search could not run, so the UI can say "searching is unavailable, type the
         * address" rather than leaving a button that silently does nothing. Reported rather than
         * thrown: discovery is a convenience and must never be the reason a transfer cannot happen.
         */
        private fun reportProblem(message: String) {
            Log.i(TAG, message)
            _problem.value = message
        }

    companion object {
        private const val TAG = "Discovery"
        private const val MULTICAST_LOCK_TAG = "scantron-transfer-discovery"

        /** Fixed by the plan so both halves agree without negotiating. */
        const val PORT = 8757

                /**
                 * Where the probe is sent: the limited broadcast address.
                 *
                 * Routers drop this, so the per-interface directed broadcast is what actually carries the
                 * probe in a warehouse; this address is kept as the last resort for a network with no
                 * usable interface mask.
                 */
                const val PROBE_HOST = "255.255.255.255"
                const val WHO_HAS = "WHO_HAS"
                const val PROBE_INTERVAL_MS = 2_000L

                /** How long one receive waits before the probe window is considered done. */
                const val RECEIVE_POLL_TIMEOUT_MS = 200

                /** Reads attempted per window; a desktop replies within milliseconds of the broadcast. */
                const val RECEIVES_PER_WINDOW = 3

                /** Assumed prefix when an interface will not report its own. */
                const val DEFAULT_PREFIX_LENGTH = 24

                /** A reply is a single short line; anything larger is not ours. */
                const val MAX_REPLY_BYTES = 512
            }
        }