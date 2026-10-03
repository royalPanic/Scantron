package com.example.scantron.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ContainerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Canonical form used for matching container ids and scanned codes, so that case and
 * surrounding whitespace never create two entries for the same thing.
 */
internal fun String.normalize(): String = trim().uppercase()

/**
 * A scan that has been received but not yet committed to a container.
 *
 * Scanning the same code repeatedly increments [count] on a single entry rather than adding
 * duplicate rows. [count] becomes the item's `quantity` when the queue is committed.
 */
data class PendingScan(
    val id: Long,
    val containerId: String,
    val event: ScanEvent,
    val count: Int = 1,
) {
    /** Identity used to merge repeat scans: the code in its normalised form. */
    val mergeKey: String get() = event.data.normalize()
}

/**
 * Coordinates hardware scans with the container/item screens.
 *
 * A scanned code is first classified:
 *  - **it already identifies a container** -> it is a navigation command. The app opens
 *    that container and the code is never offered as an item, so a container can never end up
 *    inside itself. Re-scanning the container that is already open is a no-op.
 *  - **no container is open** -> the "open existing / create new" dialog is raised so the user
 *    can decide where the code belongs.
 *  - **a container is open** -> the code is queued as a [PendingScan] and the user confirms
 *    before anything is written to the database.
 */
class ScanSessionViewModel(
    private val repository: ContainerRepository,
) : ViewModel() {

    private val _activeContainerId = MutableStateFlow<String?>(null)
    val activeContainerId: StateFlow<String?> = _activeContainerId.asStateFlow()

    private val _pendingScans = MutableStateFlow<List<PendingScan>>(emptyList())
    val pendingScans: StateFlow<List<PendingScan>> = _pendingScans.asStateFlow()

    /** Non-null while the UI must ask the user which container the scan belongs to. */
    private val _containerPrompt = MutableStateFlow<ScanEvent?>(null)
    val containerPrompt: StateFlow<ScanEvent?> = _containerPrompt.asStateFlow()

    private var nextScanId = 0L

    /**
     * Scan handling does a database lookup before deciding what to do, so concurrent scans
     * could otherwise interleave and mis-route. Serialise the whole decision instead.
     */
    private val scanMutex = Mutex()

    /** Called by the navigation layer whenever the visible container changes. */
    fun onActiveContainerChanged(containerId: String?) {
        _activeContainerId.value = containerId?.takeIf { it.isNotBlank() }
    }

    fun onScan(event: ScanEvent, onNavigateToContainer: (String) -> Unit) {
        if (!event.isValid) return
        val scannedCode = event.data.normalize()
        if (scannedCode.isEmpty()) return

        viewModelScope.launch {
            scanMutex.withLock {
                if (isExistingContainer(scannedCode)) {
                    // The code is a container tag, not an item. Open it instead of queueing
                    // it, and do nothing if it is already the container on screen.
                    if (scannedCode != _activeContainerId.value) {
                        onNavigateToContainer(scannedCode)
                    }
                    return@withLock
                }

                val active = _activeContainerId.value
                if (active == null) {
                    _containerPrompt.value = event
                } else {
                    enqueue(active, event)
                }
            }
        }
    }

    fun dismissContainerPrompt() {
        _containerPrompt.value = null
    }

    /**
     * Called when the user picks an existing container from the prompt dialog. Creates the
     * container shell when it does not exist yet, queues the held scan against it and invokes
     * [onNavigate] with the resolved container id.
     */
    fun onContainerResolved(containerId: String, onNavigate: (String) -> Unit) {
        val event = _containerPrompt.value ?: return
        val cleanId = containerId.normalize()
        if (cleanId.isEmpty()) return
        _containerPrompt.value = null

        viewModelScope.launch {
            scanMutex.withLock {
                if (!isExistingContainer(cleanId)) {
                    repository.createOrUpdateContainer(Container(id = cleanId))
                }
                enqueueUnlessItIsTheContainer(event, cleanId)
                onNavigate(cleanId)
            }
        }
    }

    /**
     * Called when the user fills in the "new container" details form from the prompt dialog.
     * The container is saved with the entered metadata before the held scan is queued.
     */
    fun onContainerCreated(container: Container, onNavigate: (String) -> Unit) {
        val event = _containerPrompt.value ?: return
        val cleanId = container.id.normalize()
        if (cleanId.isEmpty()) return
        _containerPrompt.value = null

        viewModelScope.launch {
            scanMutex.withLock {
                repository.createOrUpdateContainer(
                    container.copy(
                        id = cleanId,
                        name = container.name.trim(),
                        location = container.location.trim(),
                        notes = container.notes.trim(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                enqueueUnlessItIsTheContainer(event, cleanId)
                onNavigate(cleanId)
            }
        }
    }

    fun discardPendingScan(scanId: Long) {
        _pendingScans.update { pending -> pending.filterNot { it.id == scanId } }
    }

    fun discardAllPendingScans(containerId: String) {
        _pendingScans.update { pending -> pending.filterNot { it.containerId == containerId } }
    }

    /**
     * Persists the queued scans as items of [containerId] and clears them from the queue.
     *
     * Each entry is written as a single item whose `quantity` is the number of times that code
     * was scanned, so repeat scans never produce duplicate rows. Entries are folded in through
     * [ContainerRepository.addItemMerging], so a code that already exists in the container has
     * its quantity increased rather than being added as a second row. [onCommitted] receives the
     * total number of units added.
     */
    fun commitPendingScans(containerId: String, onCommitted: (Int) -> Unit = {}) {
        val queued = _pendingScans.value.filter { it.containerId == containerId }
        if (queued.isEmpty()) {
            onCommitted(0)
            return
        }

        viewModelScope.launch {
            queued.forEach { pending ->
                val code = pending.event.data
                repository.addItemMerging(
                    ContainerItem(
                        containerId = containerId,
                        name = code,
                        barcode = code,
                        quantity = pending.count,
                    )
                )
            }
            val ids = queued.map { it.id }.toSet()
            _pendingScans.update { pending -> pending.filterNot { it.id in ids } }
            onCommitted(queued.sumOf { it.count })
        }
    }

    private suspend fun isExistingContainer(id: String): Boolean =
        repository.getContainerDirect(id) != null

    /**
     * Queues [event] as an item of [containerId] unless the scanned code *is* that container,
     * which would mean adding the container to itself.
     */
    private fun enqueueUnlessItIsTheContainer(event: ScanEvent, containerId: String) {
        if (event.data.normalize() == containerId) return
        enqueue(containerId, event)
    }

    /**
     * Adds a scan to the queue. A code already queued for the same container has its count
     * incremented instead of adding a second row for the same barcode.
     */
    private fun enqueue(containerId: String, event: ScanEvent) {
        val key = event.data.normalize()
        _pendingScans.update { pending ->
            val index = pending.indexOfFirst {
                it.containerId == containerId && it.mergeKey == key
            }
            if (index >= 0) {
                pending.toMutableList().apply {
                    this[index] = this[index].copy(count = this[index].count + 1)
                }
            } else {
                pending + PendingScan(
                    id = nextScanId++,
                    containerId = containerId,
                    event = event,
                    count = 1,
                )
            }
        }
    }

    class Factory(private val repository: ContainerRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScanSessionViewModel(repository) as T
        }
    }
}
