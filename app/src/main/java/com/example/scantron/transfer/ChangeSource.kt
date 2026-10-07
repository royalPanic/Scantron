package com.example.scantron.transfer

import com.example.scantron.data.ContainerRepository
import com.example.scantron.data.InventoryDocument
import com.example.scantron.sync.ChangeCursors
import com.example.scantron.sync.ItemConflict
import com.example.scantron.sync.SnapshotOp
import com.example.scantron.sync.SyncOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Watches the database and produces the ops a peer still needs.
 *
 * This is the whole change detector, and it is deliberately built on observation rather than on
 * instrumentation: nothing in the scanner, the detail screen or the repository tells this class that
 * something changed. Room re-emits on any write to the observed tables, the state is read back whole,
 * and [ChangeCursors.diff] answers "which rows differ from the base" - the same question the
 * three-way merge already asks. That means a change made by a path nobody remembered to instrument is
 * still synced, which is the property that makes the feature trustworthy.
 *
 * Two design points carry real weight:
 *
 *  * **Debounce.** A warehouse scan session writes a row per trigger pull. Diffing on every write
 *    would serialise and send a message per scan; debouncing collapses a burst into one batch, which
 *    is both far less wire traffic and far fewer chances for the two devices to interleave.
 *  * **Conflicts are withheld.** A row with an open, unresolved conflict is excluded from the diff.
 *    Without that exclusion the conflicted row differs from the base forever, so it would be
 *    re-emitted on every single pass and the two devices would echo it at each other indefinitely.
 */
@OptIn(FlowPreview::class)
class ChangeSource(
    private val repository: ContainerRepository,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
) {

    /**
     * The base the diff compares against.
     *
     * Held here rather than read from disk on every pass: it is advanced in memory as batches are
     * applied, and re-reading a file per write would be both slow and racy with the writer.
     */
    private var base: InventoryDocument? = null

    /** Row identities frozen by an open conflict, so they are never re-emitted. */
    private var blockedRows: Set<String> = emptySet()

    private val _changes = MutableStateFlow<List<SyncOp>>(emptyList())

    /** The most recent diff, for tests and for a status line. */
    val lastDiff: StateFlow<List<SyncOp>> = _changes.asStateFlow()

    /**
     * Begins observing. The returned job is the caller's to cancel; a collector that outlives the
     * screen would keep reading the database and holding the SQLite observer for nothing.
     */
    fun start(scope: CoroutineScope) = scope.launch {
        combine(
            repository.allContainers,
            repository.observeDeletions(),
        ) { containers, _ -> containers }
            .debounce(debounceMillis)
            .distinctUntilChanged { old, new -> old == new }
            .collect {
                _changes.value = diffNow()
            }
    }

    /**
     * Sets the base the diff compares against, discarding any blocked rows.
     *
     * Called when a session begins and again every time a batch advances the base. Blocked rows are
     * recomputed by [setOpenConflicts] rather than cleared here, because the caller knows which
     * conflicts survived the batch and this class does not.
     */
    fun setBase(document: InventoryDocument?) {
        base = document
    }

    fun currentBase(): InventoryDocument? = base

    fun setOpenConflicts(conflicts: List<ItemConflict>) {
        blockedRows = ChangeCursors.conflictRows(conflicts)
    }

    /**
     * Computes the ops for the current database state, without waiting for a write.
     *
     * Exposed so a reconnect can re-diff immediately: an edit made while the socket was down produced
     * no observation anything was listening to, and "resync on reconnect" is precisely the call that
     * has to be made explicitly rather than waited for.
     *
     * Returns nothing before a base exists. A diff with no base is a *snapshot*, which is not a change
     * the observation loop is entitled to send: the session opening is what decides between a snapshot
     * and a batch of changes, and it has to be the only thing that does. Sending one from here would
     * duplicate the opening frame and, worse, put a whole document in a frame the desktop reads as an
     * incremental batch.
     */
    suspend fun diffNow(now: Long = System.currentTimeMillis()): List<SyncOp> {
        val knownBase = base ?: run {
            _changes.value = emptyList()
            return emptyList()
        }

        val current = repository.snapshotDocument()
        val ops = ChangeCursors.diff(base = knownBase, current = current, blocked = blockedRows, now = now)
            .filterNot { it is SnapshotOp }
        _changes.value = ops
        return ops
    }

    companion object {
        /**
         * How long a burst of writes is allowed to settle before it becomes one batch.
         *
         * 400 ms is chosen against the scanner: a trigger pull writes one row, and an operator
         * scanning a shelf pulls the trigger several times a second. This collapses a shelf into one
         * message while still feeling immediate, and it is short enough that a single deliberate edit
         * (typing a quantity, renaming a container) sends within about half a second.
         */
        const val DEFAULT_DEBOUNCE_MS = 400L
    }
}
