package com.example.scantron.ui.sync

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.scantron.data.ContainerRepository
import com.example.scantron.data.ExportImportManager
import com.example.scantron.sync.ItemConflict
import com.example.scantron.transfer.DeviceInfo
import com.example.scantron.transfer.Discovery
import com.example.scantron.transfer.Peer
import com.example.scantron.transfer.StagedPush
import com.example.scantron.transfer.SyncEngine
import com.example.scantron.transfer.SyncStatus
import com.example.scantron.transfer.SyncStore
import com.example.scantron.transfer.TransferClient
import com.example.scantron.transfer.TransferListener
import com.example.scantron.transfer.TransferOperation
import com.example.scantron.transfer.TransferResult
import com.example.scantron.transfer.TransferState
import com.example.scantron.transfer.pruneStale
import com.example.scantron.ui.transfer.TransferHostStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the Sync screen: one live-sync session, the pairing flow, and the manual file-transfer
 * fallback that used to *be* the screen.
 *
 * The division of responsibility is the same one the transfer-only screen had: the ViewModel owns
 * sequencing and what the operator sees, while [SyncEngine] owns the socket and the merge. Nothing
 * here touches the network directly, and nothing in the engine knows what a Compose state is.
 *
 * The live session is started from the screen's lifecycle callbacks rather than from a foreground
 * service, because a permanent socket on a shared warehouse network is a standing hole and on a CK65
 * it is battery the scanner needs. Backgrounding pauses; foregrounding resumes and resyncs.
 */
class SyncViewModel(
    application: Application,
    private val repository: ContainerRepository,
) : AndroidViewModel(application) {

    private val exportImportManager = ExportImportManager(application, repository)
    private val store = SyncStore(application)

    /**
     * The live-sync session.
     *
     * Constructed with the application rather than the screen so it survives a configuration change
     * the same way the ViewModel does - a session that died on rotation would force the operator to
     * re-pair every time the handheld was turned.
     */
    private val engine = SyncEngine(repository = repository, store = store)

    /** The desktop address as typed. Pre-filled with the last one that answered. */
    val address = MutableStateFlow(store.lastPairedHost() ?: "")

    /** The six-digit code the desktop is showing, as typed. */
    val pairCode = MutableStateFlow("")

    val status: StateFlow<SyncStatus> = engine.status
    val conflicts: StateFlow<List<ItemConflict>> = engine.conflicts

    /** True once this device has a base and a remembered desktop. */
    val isPaired = MutableStateFlow(store.isPaired())

    /** Whether the live session should be running, driven by the screen's lifecycle. */
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** This device's own IPv4 addresses, for the troubleshooting line. */
    private val _localAddresses = MutableStateFlow<List<String>>(emptyList())
    val localAddresses: StateFlow<List<String>> = _localAddresses.asStateFlow()

    // ---- the manual file-transfer path, demoted but still working --------------------------------

    private val hostStore = TransferHostStore(application)

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var pendingPull: PendingPull? = null
    private var pendingPush: PendingPull? = null

    private val _pullPreview = MutableStateFlow<PullPreview?>(null)

    /** Non-null exactly when a destructive import is waiting on the operator's confirmation. */
    val pullPreview: StateFlow<PullPreview?> = _pullPreview.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _listenProblem = MutableStateFlow<String?>(null)
    val listenProblem: StateFlow<String?> = _listenProblem.asStateFlow()

    private val listener = TransferListener()

    // ---- discovery -------------------------------------------------------------------------------

    private var discovery: Discovery? = null

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _discoveryUnavailable = MutableStateFlow<String?>(null)
    val discoveryUnavailable: StateFlow<String?> = _discoveryUnavailable.asStateFlow()

    private val discoveryMirrorsStarted = AtomicBoolean(false)

    init {
        address.value = store.lastPairedHost() ?: hostStore.lastHost()
        refreshLocalAddresses()
        viewModelScope.launch {
            engine.conflicts.collect { isPaired.value = store.isPaired() }
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    /**
     * Called when the screen is on screen. Starts (or resumes) the session only if the operator has
     * paired already - entering the screen must never silently open a socket to a half-configured
     * address.
     */
    fun onForeground() {
        _isForeground.value = true
        val target = address.value.trim()
        if (store.isPaired() && target.isNotEmpty()) {
            engine.resume(viewModelScope)
        }
    }

    /**
     * Called when the screen leaves. Pauses rather than stops, so the pairing and the base survive
     * and the next foreground is an incremental sync instead of a full exchange.
     */
    fun onBackground() {
        _isForeground.value = false
        engine.pause()
    }

    fun refreshLocalAddresses() {
        viewModelScope.launch {
            _localAddresses.value = DeviceInfo.ipv4Addresses()
        }
    }

    // ---- pairing ---------------------------------------------------------------------------------

    fun onAddressChanged(newAddress: String) {
        address.value = newAddress
    }

    fun onPairCodeChanged(code: String) {
        // Digits only, capped at the six the desktop shows. Filtering here rather than on submit keeps
        // a paste of "123 456" working while a paste of a whole sentence simply does nothing.
        pairCode.value = code.filter { it.isDigit() }.take(PAIR_CODE_LENGTH)
    }

    /**
     * Starts the session against the typed address and hands over the pairing code.
     *
     * Both halves happen together because they are one operator action: a code with no address has
     * nowhere to go, and an address with no code is rejected by the desktop.
     */
    fun pair() {
        val target = address.value.trim()
        if (target.isEmpty()) {
            _state.value = TransferState.Failed("Type the desktop's address first.")
            return
        }

        val code = pairCode.value.trim()
        if (code.length != PAIR_CODE_LENGTH) {
            _state.value = TransferState.Failed("Type the $PAIR_CODE_LENGTH-digit code the desktop is showing.")
            return
        }

        _state.value = TransferState.Idle
        engine.start(viewModelScope, target)
        engine.pair(code)
    }

    /** Points the session at a discovered desktop and starts it. */
    fun pairWith(peer: Peer) {
        address.value = peer.host
        stopDiscovery()
        pair()
    }

    /** Disconnects and forgets the pairing. The operator's way out of a bad pairing. */
    fun unpair() {
        engine.unpair()
        isPaired.value = false
        pairCode.value = ""
        _state.value = TransferState.Idle
    }

    /** Applies the operator's decision on one conflicted field. */
    fun resolveConflict(conflict: ItemConflict, value: String?) {
        engine.resolveConflict(conflict, value)
    }

    // ---- discovery -------------------------------------------------------------------------------

    fun canUseDiscovery(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P || hasLocationPermission()

    /**
     * Whether either location grant is held.
     *
     * From Android 12 the platform can hand back precise (FINE) or approximate (COARSE) depending on
     * what the operator chose, and the multicast lock discovery depends on is unlocked by holding
     * either. Checking FINE alone would treat an approximate grant as a refusal and re-prompt forever.
     */
    private fun hasLocationPermission(): Boolean =
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any { permission ->
            ContextCompat.checkSelfPermission(getApplication(), permission) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun startDiscovery() {
        if (!canUseDiscovery()) {
            _discoveryUnavailable.value =
                "Searching needs location access on this Android version. Type the desktop " +
                    "address above instead - it works exactly the same."
            return
        }

        val active = discovery ?: Discovery(getApplication()).also { discovery = it }

        if (discoveryMirrorsStarted.compareAndSet(false, true)) {
            viewModelScope.launch { active.peers.collect { _peers.value = it.pruneStale() } }
            viewModelScope.launch { active.isSearching.collect { _isSearching.value = it } }
            viewModelScope.launch { active.problem.collect { _discoveryUnavailable.value = it } }
        }

        _discoveryUnavailable.value = null
        active.start(viewModelScope)
    }

    fun stopDiscovery() {
        discovery?.stop()
        _isSearching.value = false
    }

    fun onDiscoveryPermissionDenied() {
        _discoveryUnavailable.value =
            "Searching needs location access, which was declined. Type the desktop address " +
                "above instead - it works exactly the same."
    }

    // ---- manual file transfer (More options) ------------------------------------------------------

    fun checkHealth() = runTransfer(TransferOperation.Health) { client ->
        val result = client.health()
        if (result is TransferResult.Success) hostStore.remember(address.value)
        result
    }

    fun sendToDesktop() = runTransfer(TransferOperation.Push) { client ->
        val json = exportImportManager.exportToJsonString()
        val result = client.push(json)
        if (result is TransferResult.Success) {
            hostStore.remember(address.value)
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
     * Deliberately does *not* import. Import clears and replaces the whole database, so the operator
     * gets to see the counts and say yes first - see [confirmPull].
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

    /**
     * Operator said yes: run the destructive import that [getFromDesktop] or a push staged.
     *
     * This is the **only** path that clears and replaces a database, and it is reachable only from the
     * manual file-transfer section. Live sync applies deltas and never touches it - which is what
     * keeps a sync that goes wrong from being able to wipe a day's scanning.
     */
    fun confirmPull() {
        val pending = pendingPull ?: pendingPush ?: return
        pendingPull = null
        pendingPush = null
        _pullPreview.value = null
        _state.value = TransferState.Working(TransferOperation.Pull)

        viewModelScope.launch {
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

    fun cancelPull() {
        pendingPull = null
        _pullPreview.value = null
        _state.value = TransferState.Idle
    }

    fun startListening() {
        if (_isListening.value) return

        val failure = listener.start()
        if (failure != null) {
            _listenProblem.value = failure
            _state.value = TransferState.Failed(failure)
            return
        }

        _listenProblem.value = null
        _isListening.value = true
        listener.onPushReceived = ::onPushStaged

        _state.value = TransferState.Success(
            "Listening on port ${listener.boundPort}. Leave this screen open and use " +
                "Send to handheld on the desktop.",
        )
    }

    fun stopListening() {
        listener.stop()
        _isListening.value = false
        _state.value = TransferState.Idle
    }

    private fun onPushStaged(push: StagedPush) {
        viewModelScope.launch {
            val preview = PullPreview(push.containerCount, push.itemCount)
            pendingPush = PendingPull(push.document, preview)
            _pullPreview.value = preview
            _state.value = TransferState.Success(
                "The desktop sent ${preview.containerCount} containers and ${preview.itemCount} " +
                    "items. Nothing has been changed yet - confirm below to replace this device's data.",
            )
        }
    }

    private fun runTransfer(
        operation: TransferOperation,
        block: suspend (TransferClient) -> TransferResult,
    ) {
        if (_state.value.isBusy) return
        _state.value = TransferState.Working(operation)

        viewModelScope.launch {
            val client = TransferClient(address.value)
            val result = block(client)
            _state.value = when (result) {
                is TransferResult.Success -> TransferState.Success(
                    result.message.ifBlank { "Done." },
                )

                is TransferResult.Failure -> TransferState.Failed(result.message)
            }
        }
    }

    /**
     * Counts a document for the push confirmation.
     *
     * Read back from the document rather than from the database so the number quoted to the operator
     * is exactly what the desktop received. Parsing is best-effort: the push already succeeded, and a
     * counting failure must not turn a good transfer into a reported error.
     */
    private fun summarizeExport(json: String): String {
        val counts = PullPreview.from(json) ?: return "your inventory"
        val items = if (counts.itemCount == 1) "1 item" else "${counts.itemCount} items"
        val containers =
            if (counts.containerCount == 1) "1 container" else "${counts.containerCount} containers"
        return "$containers and $items"
    }

    private data class PendingPull(val document: String, val preview: PullPreview)

    override fun onCleared() {
        // Everything with a socket or a lock is released with the ViewModel, not whenever the screen
        // happens to be recomposed: an engine that outlives its screen keeps a standing connection to
        // the desktop, and a held multicast lock is charged to the app's battery budget.
        engine.stop()
        listener.stop()
        discovery?.stop()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val repository: ContainerRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SyncViewModel(application, repository) as T
    }

    companion object {
        /** How many digits the desktop's pairing code has. */
        const val PAIR_CODE_LENGTH = 6
    }
}

/**
 * Counts of a document, used both to warn before a destructive import and to describe what a push
 * actually sent.
 *
 * [from] returns null rather than throwing when the document is not readable, because the desktop is
 * a peer we do not control and its `/pull` body still has to be validated by [ExportImportManager]
 * rather than by a preview parser here. The preview is a courtesy for the operator; the manager
 * remains the only thing allowed to decide whether a document is importable.
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
