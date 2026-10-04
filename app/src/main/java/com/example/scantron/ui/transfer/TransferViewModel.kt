package com.example.scantron.ui.transfer

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.scantron.data.ContainerRepository
import com.example.scantron.data.ExportImportManager
import com.example.scantron.transfer.DeviceInfo
import com.example.scantron.transfer.Discovery
import com.example.scantron.transfer.Peer
import com.example.scantron.transfer.TransferClient
import com.example.scantron.transfer.TransferOperation
import com.example.scantron.transfer.TransferResult
import com.example.scantron.transfer.TransferState
import com.example.scantron.transfer.pruneStale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the transfer screen: holds the operator's address, runs one transfer at a time, and
 * keeps the results for display.
 *
 * The ViewModel owns the sequencing, not the network. [TransferClient] does the HTTP and
 * [ExportImportManager] does the document work; this class is only responsible for not letting
 * two transfers overlap and for making sure a destructive pull-import is confirmed first.
 */
class TransferViewModel(
    application: Application,
    private val repository: ContainerRepository,
) : AndroidViewModel(application) {

    private val exportImportManager = ExportImportManager(application, repository)
    private val hostStore = TransferHostStore(application)

    /** The desktop address as typed. Pre-filled from the last successful transfer. */
    val address = MutableStateFlow(hostStore.lastHost())

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    /**
     * A pull that has been fetched but not yet confirmed.
     *
     * The document is held in memory between fetching and confirming so the operator is not made
     * to re-download a megabyte because they thought about it. It is *not* imported here: the
     * screen shows the counts and waits for an explicit yes.
     */
    private var pendingPull: PendingPull? = null

    private val _pullPreview = MutableStateFlow<PullPreview?>(null)

    /** Non-null exactly when a destructive import is waiting on the operator's confirmation. */
    val pullPreview: StateFlow<PullPreview?> = _pullPreview.asStateFlow()

    /** This device's own IPv4 addresses, for the troubleshooting line. */
    private val _localAddresses = MutableStateFlow<List<String>>(emptyList())
    val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

        // Discovery is optional by construction: it is only constructed once the operator has asked
        // for it and granted location permission, so manual-IP transfer is never waiting on it.
        private var discovery: Discovery? = null

        private val _peers = MutableStateFlow<List<Peer>>(emptyList())
        val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

        private val _isSearching = MutableStateFlow(false)
        val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

        /**
         * Why the Find desktops button is unavailable, phrased as guidance. Null when it is usable.
         */
        private val _discoveryUnavailable = MutableStateFlow<String?>(null)
        val discoveryUnavailable: StateFlow<String?> = _discoveryUnavailable.asStateFlow()

        init {
            refreshLocalAddresses()
        }

    fun onAddressChanged(newAddress: String) {
        address.value = newAddress
    }

        /**
         * True when the runtime prerequisites for discovery are satisfied.
         *
         * The caller checks this before showing the permission prompt, so the operator is never asked
         * to grant a permission that would not help. From API 28 the platform will not deliver
         * multicast or scan results without location permission; below that it is not required, and
         * asking for it anyway would be a pointless prompt.
         */
        fun canUseDiscovery(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

        /**
         * Begins searching for desktops.
         *
         * Assumes [canUseDiscovery] has already been confirmed by the screen. Deliberately tolerant
         * of failure: if the search cannot run, [discoveryUnavailable] explains why and the address
         * field stays the supported way to transfer.
         */
        fun startDiscovery() {
            if (!canUseDiscovery()) {
                _discoveryUnavailable.value =
                    "Searching needs location access on this Android version. Type the desktop " +
                        "address above instead - it works exactly the same."
                return
            }

            val active = discovery ?: Discovery(getApplication()).also { discovery = it }

            // Mirror the discovery flows into state the screen can collect. Started once per
            // Discovery instance; starting them again on every tap would double-count peers.
            if (discoveryMirrorsStarted.compareAndSet(false, true)) {
                viewModelScope.launch {
                    active.peers.collect { _peers.value = it.pruneStale() }
                }
                viewModelScope.launch {
                    active.isSearching.collect { _isSearching.value = it }
                }
                viewModelScope.launch {
                    active.problem.collect { problem -> _discoveryUnavailable.value = problem }
                }
            }

            _discoveryUnavailable.value = null
            active.start(viewModelScope)
        }

        fun stopDiscovery() {
            discovery?.stop()
            _isSearching.value = false
        }

        /**
         * The operator declined the location prompt.
         *
         * Not an error state and not a nag: searching will not work, and everything else still does.
         * The message says so plainly so the operator is never left wondering why the button appeared
         * to do nothing.
         */
        fun onDiscoveryPermissionDenied() {
            _discoveryUnavailable.value =
                "Searching needs location access, which was declined. Type the desktop address " +
                    "above instead - it works exactly the same."
        }

        /** Operator tapped a discovered desktop: fill the address field with it. */
        fun onPeerSelected(peer: Peer) {
            address.value = peer.host
            stopDiscovery()
        }

    /**
     * `GET /health`.
     *
     * Worth having as its own button: "cannot reach the desktop" and "the desktop refused this
     * document" are different problems, and `/health` is the cheapest way to tell them apart
     * before the operator starts a transfer that will overwrite something.
     */
    fun checkHealth() = runTransfer(TransferOperation.Health) { client ->
        val result = client.health()
        if (result is TransferResult.Success) {
            // Only a desktop that actually answered is worth remembering.
            hostStore.remember(address.value)
        }
        result
    }

    /**
     * Push this device's inventory to the desktop.
     *
     * `exportToJsonString` produces the same document a USB export writes, byte for byte, and it
     * goes over the wire untouched.
     */
    fun sendToDesktop() = runTransfer(TransferOperation.Push) { client ->
        val json = exportImportManager.exportToJsonString()
        val result = client.push(json)
        if (result is TransferResult.Success) {
            hostStore.remember(address.value)
            // Count what we sent, because the desktop shows a merge/conflict state afterwards and
            // an operator who sees nothing happen will assume the push failed.
            val summary = summarizeExport(json)
            result.copy(
                message = "Sent $summary to the desktop. The desktop will show anything it " +
                    "could not merge automatically.",
            )
        } else {
            result
        }
    }

    /**
     * Fetch the desktop's document and hold it for confirmation.
     *
     * Deliberately does *not* import. Import clears and replaces the whole database, so the
     * operator gets to see the counts and say yes first - see [confirmPull].
     */
    fun getFromDesktop() = runTransfer(TransferOperation.Pull) { client ->
        when (val result = client.pull()) {
            is TransferResult.Failure -> result
            is TransferResult.Success -> {
                val preview = PullPreview.from(result.body)
                if (preview == null) {
                    TransferResult.Failure(
                        "The desktop sent a document this device cannot read. Nothing was changed.",
                    )
                } else {
                    _pullPreview.value = preview
                    pendingPull = PendingPull(result.body, preview)
                    result.copy(
                        message = "The desktop has ${preview.containerCount} containers and " +
                            "${preview.itemCount} items. Nothing has been changed yet.",
                    )
                }
            }
        }
    }

    /** Operator said yes: run the destructive import that [getFromDesktop] fetched. */
    fun confirmPull() {
        val pending = pendingPull ?: return
        pendingPull = null
        _pullPreview.value = null
        _state.value = TransferState.Working(TransferOperation.Pull)

        viewModelScope.launch {
            // Deliberately the same import path a USB file takes, including the ordering that
            // clears the database only after the document has fully parsed and validated.
            val result = exportImportManager.importFromJsonString(
                json = pending.document,
                source = "desktop at ${address.value}",
            )
            _state.value = if (result.success) {
                TransferState.Success(
                    "Replaced this device's inventory with ${result.containerCount} containers " +
                        "and ${result.itemCount} items from the desktop.",
                )
            } else {
                            TransferState.Failed(result.message)
            }
        }
    }

    /** Operator said no: drop the fetched document and leave the database alone. */
    fun cancelPull() {
        pendingPull = null
        _pullPreview.value = null
        _state.value = TransferState.Idle
    }

    fun clearStatus() {
        if (_state.value !is TransferState.Working) _state.value = TransferState.Idle
    }

    fun refreshLocalAddresses() {
        viewModelScope.launch {
            _localAddresses.value = DeviceInfo.ipv4Addresses()
        }
    }

    /**
     * Runs exactly one transfer, and refuses to start a second while one is in flight.
     *
     * The hub handles one request at a time, so overlapping requests would queue on the desktop
     * at best and interleave at worst. The guard is here rather than in the UI so that it holds
     * however the screen is driven.
     */
    private fun runTransfer(
        operation: TransferOperation,
        block: suspend (TransferClient) -> TransferResult,
    ) {
        if (_state.value.isBusy) return
        val host = address.value.trim()
        if (host.isEmpty()) {
            _state.value = TransferState.Failed("Enter the desktop's address first.")
            return
        }

        _state.value = TransferState.Working(operation)
        viewModelScope.launch {
            val client = TransferClient(host = host)
            _state.value = when (val result = block(client)) {
                is TransferResult.Success -> TransferState.Success(result.message)
                is TransferResult.Failure -> TransferState.Failed(result.message)
            }
        }
    }

    /**
     * Counts what a pushed document contained, straight off the wire bytes.
     *
     * Read back from the document rather than from the database so the number quoted to the
     * operator is exactly what the desktop received. Parsing is best-effort: the push already
     * succeeded, and a counting failure must not turn a good transfer into a reported error.
     */
    private fun summarizeExport(json: String): String {
        val counts = PullPreview.from(json) ?: return "your inventory"
        val items = if (counts.itemCount == 1) "1 item" else "${counts.itemCount} items"
        val containers =
            if (counts.containerCount == 1) "1 container" else "${counts.containerCount} containers"
        return "$containers and $items"
    }

    private data class PendingPull(val document: String, val preview: PullPreview)

    /** Guards against stacking a second set of flow collectors on one [Discovery]. */
    private val discoveryMirrorsStarted = AtomicBoolean(false)

    override fun onCleared() {
        // Release the socket and the multicast lock with the ViewModel, not whenever the screen
        // happens to be recomposed: a held multicast lock is charged to the app's battery budget.
        discovery?.stop()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val repository: ContainerRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TransferViewModel(application, repository) as T
    }
}

/**
 * Counts of a document, used both to warn before a destructive import and to describe what a
 * push actually sent.
 *
 * [from] returns null rather than throwing when the document is not readable, because the
 * desktop is a peer we do not control and its `/pull` body still has to be validated by
 * [ExportImportManager] rather than by a preview parser here. The preview is a courtesy for the
 * operator; the manager remains the only thing allowed to decide whether a document is importable.
 */
data class PullPreview(val containerCount: Int, val itemCount: Int) {

    companion object {
        fun from(json: String): PullPreview? = runCatching {
                    val root = JSONObject(json)
            val containers = root.optJSONArray("containers") ?: return null
            var itemCount = 0
            for (i in 0 until containers.length()) {
                itemCount += containers.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
            }
            PullPreview(containers.length(), itemCount)
        }.getOrNull()
    }
}