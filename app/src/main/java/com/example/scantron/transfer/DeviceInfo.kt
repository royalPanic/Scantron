package com.example.scantron.transfer

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The handheld's own identity on the network, for display only.
 *
 * This exists purely as a troubleshooting affordance. The topology is one-directional - the
 * desktop hosts and the handheld connects - so the device's IP is never needed to make a
 * transfer work. It matters when something *isn't* working: "is this handheld even on the
 * warehouse Wi-Fi?" is the first question, and the answer is much easier to give when the screen
 * can show the operator which network it is actually on.
 *
 * Hence no attempt is made to reach any hub from here. This type never opens a socket.
 */
object DeviceInfo {

    private const val TAG = "DeviceInfo"
    private const val WIFI_INTERFACE_PREFIX = "wlan"

    /**
     * Best-effort model name. Honeywell exposes this via `Build.MODEL` on the CK65; there is no
     * public API for the marketing name, so the raw value is shown and it is left to the
     * operator to recognise it.
     */
    fun deviceName(): String = Build.MODEL ?: "Android device"

    /**
     * The device's routable IPv4 addresses, Wi-Fi first.
     *
     * Loopback, link-local and IPv6 are omitted deliberately: the operator needs to read an
     * address they can actually use, and `127.0.0.1` or a `fe80::` link-local address would only
     * waste their time. An empty list is a normal and meaningful answer - it means the device
     * holds no routable IPv4 address, which on a CK65 almost always means the Wi-Fi is not
     * associated.
     *
     * Wi-Fi sorts first because that is the interface a warehouse desktop could reach us on; a
     * VPN or ethernet address is rarely the one an operator is chasing.
     */
    suspend fun ipv4Addresses(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<String> = withContext(ioDispatcher) {
        // NetworkInterface enumeration is allowed to fail (a locked-down device profile can
        // deny it); an empty list is a better answer than a crash on a diagnostics screen.
        val addresses = runCatching { collectInterfaceAddresses() }
            .onFailure { Log.w(TAG, "Could not enumerate network interfaces", it) }
            .getOrDefault(emptyList())

        addresses
            .sortedWith(
                compareBy(
                    { if (it.interfaceName.startsWith(WIFI_INTERFACE_PREFIX)) 0 else 1 },
                    { it.interfaceName },
                ),
            )
            .map { it.address }
            .distinct()
    }

    /**
     * Resolves the SSID the device is associated with, or `null` if it cannot be determined.
     *
     * `null` is a common and expected answer, not a failure: from API 27 the platform returns a
     * redacted placeholder unless the app holds location permission, and this screen must work
     * for the common case where the operator has declined it - which is also the case where
     * discovery is unavailable. Returning null lets the UI say nothing rather than print
     * `<unknown ssid>` as though it were the network name.
     */
    fun currentWifiName(context: Context): String? = runCatching {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null

        // Deprecated on API 31+ but still the only accessor that works across the whole
        // minSdk 24 range this app supports; the newer API returns the same redacted value
        // unless location permission is held.
        @Suppress("DEPRECATION")
        val ssid = wifiManager.connectionInfo?.ssid?.toString().orEmpty()

        ssid.trim('"').takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
    }.getOrNull()

    private data class InterfaceAddress(val address: String, val interfaceName: String)

    /** Every non-loopback, non-link-local IPv4 address on the device, with its interface name. */
    private fun collectInterfaceAddresses(): List<InterfaceAddress> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList().mapNotNull { address ->
                    val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) return@mapNotNull null
                    InterfaceAddress(ipv4.hostAddress.orEmpty(), networkInterface.name)
                }
            }
}