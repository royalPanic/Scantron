package com.example.scantron.transfer

import android.util.Log
import com.example.scantron.data.ContainerRepository
import com.example.scantron.data.InventoryDocument
import com.example.scantron.sync.ChangeCursors
import com.example.scantron.sync.ItemConflict
import com.example.scantron.sync.SnapshotOp
import com.example.scantron.sync.SyncApply
import com.example.scantron.sync.SyncJson
import com.example.scantron.sync.SyncMessage
import com.example.scantron.sync.SyncMessageType
import com.example.scantron.sync.SyncOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

/** Where a live-sync session currently stands, for the status block on the Sync screen. */
sealed interface SyncStatus {
    /** Nothing running. The operator has not paired, or has stopped the session. */
    data object Idle : SyncStatus

    /** Connected and exchanging the pairing code and the opening frames. */
    data object Pairing : SyncStatus

    /** A batch is being exchanged or applied right now. */
    data object Syncing : SyncStatus

    /** Both devices agree as of [atMillis], on this device's clock. */
    data class InSync(val atMillis: Long) : SyncStatus

    /**
     * The connection is down and the engine is retrying on its own.
     *
     * A distinct state from [Idle] because the two mean opposite things to an operator: Idle says
     * "nothing is happening and nothing will", Offline says "leave the screen open and it will
     * come back".
     */
    data class Offline(val reason: String) : SyncStatus

    /** Open conflicts are being held, waiting for the operator to choose a value. */
    data class Conflicts(val count: Int) : SyncStatus
}

/**
 * Drives a live-sync session for as long as the app is in the foreground.
 *
 * The lifecycle it implements is the contract the desktop speaks:
 *
 * ```text
 * connect -> hello -> pair(code) -> paired
 *   -> (no usable base ? snapshot : changes) both ways
 *   -> steady state: local diffs out, remote ops in, ack
 *   -> bye
 * ```
 *
 * Three decisions in here are load-bearing:
 *
 *  * **Only non-conflicting remote ops are auto-applied.** A conflicting op is held as an open
 *    conflict and the base does *not* advance for it. Advancing the base would declare one side the
 *    winner, silently discarding the other - the exact failure this design exists to prevent.
 *  * **Every reconnect re-diffs against the base.** An edit made while the socket was down produced
 *    no observation anything was listening to, so the reconciliation has to be run explicitly rather
 *    than waited for. This is why an offline edit reconciles correctly without any cross-session
 *    clock agreement.
 *  * **A replay is a no-op.** The base advances only for what both sides agreed on, so applying the
 *    same batch twice lands the same result and reports no new change. That is what makes
 *    reconnect-and-resend safe with no de-duplication bookkeeping.
 *
 * Deliberately *not* a foreground service. A permanent socket on a shared warehouse network is a
 * standing hole, and on a CK65 it is battery the scanner needs. The session lives while the screen
 * does; backgrounding pauses it and foregrounding resumes it and resyncs.
 */
class SyncEngine(
    private val repository: ContainerRepository,
    private val store: SyncStore,
    private val clientFactory: (host: String, port: Int) -> WebSocketClient = { host, port ->
        WebSocketClient(host = host, port = port)
    },
    private val changeSource: ChangeSource = ChangeSource(repository),
) {

    private val tag = "SyncEngine"

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Conflicts held for the operator's decision, newest last. */
    private val _conflicts = MutableStateFlow<List<ItemConflict>>(emptyList())
    val conflicts: StateFlow<List<ItemConflict>> = _conflicts.asStateFlow()

    /** The desktop this session is pointed at, or null when not paired. */
    private val _host = MutableStateFlow<String?>(null)
    val host: StateFlow<String?> = _host.asStateFlow()

    /** Serialises every send, because two coroutines can want the socket at the same moment. */
    private val sendMutex = Mutex()

    private var sessionJob: Job? = null
    private var changeJob: Job? = null

    private var sender: WebSocketClient.TextSender? = null

    /** Resume the session and resync when the screen comes back to the foreground. */
    private var observerScope: CoroutineScope? = null

    /**
     * Starts (or restarts) a session against [host].
     *
     * Safe to call repeatedly: an existing session for the same host is left alone, and a session for
     * a different host is stopped first so two sockets can never be open at once.
     */
    fun start(scope: CoroutineScope, host: String, port: Int = TransferClient.DEFAULT_PORT) {
        observerScope = scope
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            _status.value = SyncStatus.Offline("Type the desktop's address first.")
            return
        }

        if (sessionJob?.isActive == true && _host.value == trimmed) return

        stop()
        _host.value = trimmed
        store.rememberPairedHost(trimmed)
        startChangeObserver(scope)
        sessionJob = scope.launch { runSession(trimmed, port) }
    }

    /** Stops the session and the database observation. Called when the screen goes away or pauses. */
    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        changeJob?.cancel()
        changeJob = null
        sender = null
        _status.value = SyncStatus.Idle
    }

    /**
     * Pauses on background, keeping the pairing and the base.
     *
     * Distinct from [stop] because the operator has not asked to disconnect - the app simply is not
     * on screen. Keeping the base means the resume is an incremental one rather than a full
     * exchange, which on a warehouse Wi-Fi is the difference between instant and several seconds.
     */
    fun pause() {
        sessionJob?.cancel()
        sessionJob = null
        changeJob?.cancel()
        changeJob = null
        sender = null
        _status.value = SyncStatus.Idle
    }

    /** Resumes and immediately resyncs, because edits may have been made while paused. */
    fun resume(scope: CoroutineScope) {
        val target = _host.value ?: store.lastPairedHost() ?: return
        observerScope = scope
        startChangeObserver(scope)
        sessionJob = scope.launch {
            // The re-diff runs before the socket does, so the opening `changes` already carries
            // anything that happened while the session was down.
            delay(RESUME_SETTLE_MS)
            runSession(target, TransferClient.DEFAULT_PORT)
        }
    }

    /** Forgets the pairing and the base. The operator's way out of a bad pairing. */
    fun unpair() {
        stop()
        store.forgetPairing()
        _host.value = null
        _conflicts.value = emptyList()
    }

    /** Applies an operator's decision on one conflicted field and sends it to the desktop. */
    fun resolveConflict(conflict: ItemConflict, value: String?) {
        val scope = observerScope ?: return
        scope.launch {
            val op = com.example.scantron.sync.ResolveOp(
                containerId = conflict.containerId,
                itemUuid = conflict.itemUuid,
                field = conflict.field,
                value = value,
                at = store.nextClock(),
            )

            // Applied locally first: the operator is standing at this device, so its view must
            // reflect the decision immediately even if the socket is down.
            val base = changeSource.currentBase() ?: repository.snapshotDocument()
            val result = SyncApply.apply(base = base, local = base, ops = listOf(op))
            repository.applyDocumentDelta(result.document)
            changeSource.setBase(result.base)
            store.writeBase(result.base)

            _conflicts.value = _conflicts.value.filterNot { sameConflict(it, conflict) }
            changeSource.setOpenConflicts(_conflicts.value)

            sendMessage(SyncMessage(type = SyncMessageType.Resolve, ops = listOf(op)))
        }
    }

    /**
     * The session loop: connect, exchange, retry, forever.
     *
     * Backoff grows from [INITIAL_BACKOFF_MS] to [MAX_BACKOFF_MS]. The cap matters more than the
     * growth - an uncapped backoff on a device an operator is watching becomes "it never comes back",
     * and a warehouse AP can drop a client for a minute at a time without anything being wrong.
     */
    private suspend fun runSession(host: String, port: Int) {
        var backoff = INITIAL_BACKOFF_MS

        while (observerScope?.isActive == true) {
            _status.value = SyncStatus.Pairing

            val client = clientFactory(host, port)
            val outcome = try {
                client.connectAndPump(
                    onOpen = { send -> onSessionOpen(send) },
                    onText = { text -> onTextFrame(text) },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The client reports its own failures as values; anything thrown here is a bug in this
                // class, so it is turned into a status rather than allowed to kill the retry loop.
                SyncResult.Failure("Live sync could not continue.")
            }

            sender = null

            if (outcome is SyncResult.Closed) {
                // The desktop said goodbye in words; the reason is the whole message, and it is more
                // useful on screen than any wording this class could invent.
                _status.value = SyncStatus.Offline(outcome.reason)
                backoff = INITIAL_BACKOFF_MS
            } else {
                _status.value = SyncStatus.Offline(outcome.message)
            }

            delay(backoff)
            backoff = min(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    /**
     * The first thing that happens on a fresh socket: announce who we are, and ask to pair.
     *
     * The pairing code is not held here - it is supplied by the operator on the screen and sent by
     * [pair] - because a session that reconnected silently is a session the operator has not
     * authorised, and asking for the code again is the honest behaviour.
     */
    private suspend fun onSessionOpen(send: WebSocketClient.TextSender) {
        sender = send

        val hello = SyncMessage(
            type = SyncMessageType.Hello,
            seq = store.nextClock(),
            src = store.deviceId(),
            hello = com.example.scantron.sync.HelloInfo(
                deviceId = store.deviceId(),
                name = android.os.Build.MODEL ?: "Android device",
                protocolVersion = SyncJson.PROTOCOL_VERSION,
            ),
        )

        sendRaw(hello)

        pendingPairCode?.let { code ->
            pendingPairCode = null
            sendRaw(SyncMessage(type = SyncMessageType.Pair, seq = store.nextClock(), src = store.deviceId(), pair = com.example.scantron.sync.PairInfo(code)))
        }
    }

    /** The pairing code the operator typed, held only until the next connection opens. */
    private var pendingPairCode: String? = null

    /**
     * Hands the operator's pairing code to the session.
     *
     * If a socket is already open the code goes out on it immediately; otherwise it is held for the
     * next connection, so typing a code while the desktop is briefly unreachable still pairs rather
     * than being silently dropped.
     */
    fun pair(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return

        val scope = observerScope
        if (scope != null && sessionJob?.isActive != true) {
            val target = _host.value ?: store.lastPairedHost()
            if (target != null) {
                pendingPairCode = trimmed
                store.rememberPairedHost(target)
                startChangeObserver(scope)
                sessionJob = scope.launch { runSession(target, TransferClient.DEFAULT_PORT) }
                return
            }
        }

        if (sender == null) {
            pendingPairCode = trimmed
            return
        }

        scope?.launch {
            sendMessage(
                SyncMessage(
                    type = SyncMessageType.Pair,
                    seq = store.nextClock(),
                    src = store.deviceId(),
                    pair = com.example.scantron.sync.PairInfo(trimmed),
                ),
            )
        }
    }

    /** Starts observing the database and mirrors every burst of local edits onto the wire. */
    private fun startChangeObserver(scope: CoroutineScope) {
        if (changeJob?.isActive == true) return

        changeSource.setBase(store.readBase())
        changeSource.setOpenConflicts(_conflicts.value)

        changeJob = changeSource.start(scope)
        scope.launch {
            changeSource.lastDiff.collect { ops ->
                // A snapshot is not a change. Only the session opening may decide that a whole document
                // needs to go, because only it knows whether the desktop already holds a base.
                val changes = ops.filterNot { it is SnapshotOp }
                if (changes.isEmpty()) return@collect
                sendMessage(SyncMessage(type = SyncMessageType.Changes, ops = changes))
            }
        }
    }

    /**
     * Handles one frame from the desktop.
     *
     * Returns false only when the session should end, which is currently never: a frame this build
     * does not understand is a reason to say so and keep the socket, not to drop it and force the
     * operator to re-pair.
     */
    private suspend fun onTextFrame(text: String): Boolean {
        when (val read = SyncJson.read(text)) {
            is SyncJson.ReadResult.Invalid -> {
                Log.w(tag, "Desktop frame refused: ${read.reason}")
                _status.value = SyncStatus.Offline(read.reason)
                return false
            }

            is SyncJson.ReadResult.Parsed -> handle(read.message)
        }

        return true
    }

    private suspend fun handle(message: SyncMessage) {
        // The peer's clock is observed first, so nothing this device sends next can reuse a number
        // the peer has already used.
        if (message.seq > 0) store.observeClock(message.seq)

        when (message.type) {
            SyncMessageType.Paired -> {
                _status.value = SyncStatus.Syncing
                sendOpeningChanges()
            }

            SyncMessageType.Snapshot, SyncMessageType.Changes -> applyRemoteBatch(message)

            SyncMessageType.Conflict -> recordRemoteConflict(message)

            SyncMessageType.Resolve -> applyRemoteResolution(message)

            SyncMessageType.Ping -> { /* The frame loop already answers a WebSocket ping. */ }

            SyncMessageType.Pong -> Unit

            SyncMessageType.Bye -> {
                _status.value = SyncStatus.Offline(message.reason ?: "The desktop ended the sync.")
            }

            // Frames only this device sends. Receiving one means the desktop is echoing, which is
            // harmless to ignore but worth a line in the log if it ever happens.
            SyncMessageType.Hello, SyncMessageType.Pair, SyncMessageType.Ack ->
                Log.d(tag, "Ignoring a ${SyncJson.wireName(message.type)} frame from the desktop")
        }
    }

    /**
     * Sends this device's view on a freshly paired session.
     *
     * A snapshot when there is no usable base, and a diff otherwise. "Usable" excludes a base older
     * than the tombstone horizon: a peer that has been away that long cannot be sent an incremental
     * replay with any confidence, because its own base may predate the horizon too, so the full
     * document - which states what the inventory *is* rather than what changed - is the only
     * instruction it can act on.
     */
    private suspend fun sendOpeningChanges() {
        val base = if (store.baseExists() && store.baseAgeMillis() <= SyncStore.TOMBSTONE_HORIZON_MS) {
            store.readBase()
        } else {
            null
        }

        // The base this diff uses is the last one both devices agreed on, and it is *not* advanced
        // here. Claiming the current local state as the base before the desktop has answered would be
        // claiming the desktop agrees with edits it has not seen yet.
        changeSource.setBase(base)
        changeSource.setOpenConflicts(_conflicts.value)

        // Prune on the handshake, not on every write: the horizon is measured in weeks, and doing it
        // here means it happens exactly as often as it can matter.
        repository.pruneDeletions(System.currentTimeMillis() - SyncStore.TOMBSTONE_HORIZON_MS)

        if (base == null) {
            // No usable base, so a full snapshot is the only instruction the desktop can act on. An
            // empty document still has to be sent: "I hold nothing" is a fact the desktop needs, and
            // omitting the frame would leave it believing the previous session's state still stands.
            val current = repository.snapshotDocument()
            sendMessage(SyncMessage(type = SyncMessageType.Snapshot, ops = listOf(snapshotOp(current))))
            return
        }

        // A base exists, so only genuinely local edits go out - and an empty batch is not sent at all,
        // because "nothing changed" is exactly the case where a frame is noise.
        val ops = ChangeCursors.diff(
            base = base,
            current = repository.snapshotDocument(),
            blocked = ChangeCursors.conflictRows(_conflicts.value),
        )

        if (ops.isNotEmpty()) {
            sendMessage(SyncMessage(type = SyncMessageType.Changes, ops = ops))
        }

        if (_conflicts.value.isEmpty()) {
            _status.value = SyncStatus.InSync(System.currentTimeMillis())
        }
    }

    private fun snapshotOp(document: InventoryDocument) = SnapshotOp(
        json = com.example.scantron.data.ExportImportManager.buildDocument(
            document.containers,
            document.items,
        ),
        document = document,
    )

    /**
     * Applies a batch of remote ops.
     *
     * Non-conflicting ops are written through the repository and the base advances for them, then an
     * `ack` carrying the batch's sequence goes back - which is what tells the desktop the ops are
     * durably applied rather than merely received.
     */
    private suspend fun applyRemoteBatch(message: SyncMessage) {
        _status.value = SyncStatus.Syncing

        val base = changeSource.currentBase()
        val local = repository.snapshotDocument()
        val result = SyncApply.apply(base = base, local = local, ops = message.ops)

        // Only the delta is written. A full replace here would be a much larger write and - worse - a
        // window in which a scan landing mid-apply could be lost.
        repository.applyDocumentDelta(result.document)

        // The base is advanced for what both sides agreed on and deliberately not for a conflicted
        // field, which is what keeps the conflict open until a human settles it.
        changeSource.setBase(result.base)
        store.writeBase(result.base)

        if (result.conflicts.isNotEmpty()) {
            _conflicts.value = mergeConflicts(_conflicts.value, result.conflicts)
            changeSource.setOpenConflicts(_conflicts.value)
        }

        if (message.seq > 0) {
            sendMessage(SyncMessage(type = SyncMessageType.Ack, ackSeq = message.seq))
        }

        _status.value = if (_conflicts.value.isEmpty()) {
            SyncStatus.InSync(System.currentTimeMillis())
        } else {
            SyncStatus.Conflicts(_conflicts.value.size)
        }
    }

    /** Records a conflict the desktop reported, so both devices show the same list. */
    private fun recordRemoteConflict(message: SyncMessage) {
        val info = message.conflict ?: return
        val conflict = ItemConflict(
            containerId = info.containerId,
            itemUuid = info.itemUuid,
            itemName = info.itemName,
            field = info.field,
            baseValue = info.baseValue,
            localValue = info.localValue,
            remoteValue = info.remoteValue,
        )

        if (_conflicts.value.none { sameConflict(it, conflict) }) {
            _conflicts.value = _conflicts.value + conflict
            changeSource.setOpenConflicts(_conflicts.value)
        }

        _status.value = SyncStatus.Conflicts(_conflicts.value.size)
    }

    /**
     * Applies the desktop's decision on a conflict.
     *
     * A decision is not a simultaneous edit, so it simply sets the field and the conflict clears on
     * both sides - which is why this path advances the base where the conflicted batch did not.
     */
    private suspend fun applyRemoteResolution(message: SyncMessage) {
        val ops = message.ops.filterIsInstance<com.example.scantron.sync.ResolveOp>()
        if (ops.isEmpty()) return

        val base = changeSource.currentBase()
        val result = SyncApply.apply(base = base, local = repository.snapshotDocument(), ops = ops)
        repository.applyDocumentDelta(result.document)
        changeSource.setBase(result.base)
        store.writeBase(result.base)

        val resolved = ops.map { op ->
            ResolveKey(op.containerId, op.itemUuid, op.field)
        }.toSet()

        _conflicts.value = _conflicts.value.filterNot {
            ResolveKey(it.containerId, it.itemUuid, it.field) in resolved
        }
        changeSource.setOpenConflicts(_conflicts.value)
    }

    private suspend fun sendMessage(message: SyncMessage) {
        val framed = message.copy(seq = if (message.seq > 0) message.seq else store.nextClock())
            .let { if (it.src.isEmpty()) it.copy(src = store.deviceId()) else it }

        sendRaw(framed)
    }

    private suspend fun sendRaw(message: SyncMessage) {
        sendMutex.withLock {
            val send = sender ?: return
            runCatching { send.sendText(SyncJson.write(message)) }
                .onFailure { Log.w(tag, "Could not send a ${SyncJson.wireName(message.type)} frame", it) }
        }
    }

    /**
     * Merges newly reported conflicts into the held list, keyed on (container, row, field).
     *
     * A conflict already held is replaced rather than appended: the two devices report the same
     * conflict with the same key, and duplicating it would show the operator the same decision twice.
     */
    private fun mergeConflicts(
        existing: List<ItemConflict>,
        incoming: List<ItemConflict>,
    ): List<ItemConflict> {
        val merged = existing.toMutableList()

        for (conflict in incoming) {
            val index = merged.indexOfFirst { sameConflict(it, conflict) }
            if (index >= 0) merged[index] = conflict else merged += conflict
        }

        return merged
    }

    private fun sameConflict(left: ItemConflict, right: ItemConflict): Boolean =
        sameConflictKey(left, right)

    /** The (container, row, field) triple a resolve op names. */
    private data class ResolveKey(val containerId: String, val itemUuid: String, val field: String)

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L

        /** A brief settle before resuming, so a quick app switch does not churn a socket. */
        const val RESUME_SETTLE_MS = 250L
    }
}

/**
 * The conflict's stable key, so the engine and the UI agree on which one was decided.
 *
 * Keyed on (container, row, field) rather than on a generated id, because the two devices report the
 * same conflict independently and their ids would not match - which would show the operator the same
 * decision twice and let one of them linger unresolved.
 */
internal fun sameConflict(left: ItemConflict, right: ItemConflict): Boolean =
    left.containerId == right.containerId &&
        left.itemUuid == right.itemUuid &&
        left.field == right.field

/** Alias used inside the engine, where the receiver reads as the subject of the comparison. */
private fun sameConflictKey(left: ItemConflict, right: ItemConflict): Boolean = sameConflict(left, right)
