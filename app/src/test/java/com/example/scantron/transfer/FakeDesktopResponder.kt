package com.example.scantron.transfer

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A stand-in for the desktop's discovery responder, so the handheld's probe can be exercised over a
 * real socket with no PC present - the UDP mirror of [FakeHubServer].
 *
 * Binds loopback on the fixed discovery port, answers [Discovery.WHO_HAS] with a well-formed
 * `SCANTRON_HUB/1 ...` reply (unicast back to the sender), and ignores anything else. Handles one
 * datagram at a time on a daemon thread, matching the desktop responder's sequential design.
 */
class FakeDesktopResponder(
    private val port: Int = Discovery.PORT,
    private val name: String = "DESKTOP-TEST",
    private val hubPort: Int = 8756,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: DatagramSocket? = null

    /** Binds and starts answering. Throws [java.net.SocketException] if the port is unavailable. */
    fun start() {
        if (running.getAndSet(true)) return

        val server = DatagramSocket(port, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = RECEIVE_TIMEOUT_MS
        }
        socket = server

        Thread({ serve(server) }, "fake-desktop-responder").apply { isDaemon = true }.start()
    }

    private fun serve(server: DatagramSocket) {
        val buffer = ByteArray(512)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            if (!runCatching { server.receive(packet) }.isSuccess) continue

            val payload = String(buffer, 0, packet.length, Charsets.UTF_8).trim()
            if (payload != Discovery.WHO_HAS) continue

            val reply = "${Peer.PREFIX} $name ${packet.address.hostAddress} $hubPort"
                .toByteArray(Charsets.UTF_8)
            runCatching {
                server.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }
    }

    /** Closes the socket. Safe to call when not started. */
    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val RECEIVE_TIMEOUT_MS = 200
    }
}
