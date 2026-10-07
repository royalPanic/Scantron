package com.example.scantron.sync

import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ExportImportManager
import com.example.scantron.data.InventoryDocument
import com.example.scantron.data.ItemKey
import com.example.scantron.data.itemKeyOf

/**
 * Turns "what we hold now" and "what we last agreed on" into the ops a peer still needs.
 *
 * A port of the desktop's `Sync/ChangeCursors.cs`. This single rule does two jobs, and that is the
 * point of the design:
 *
 *  1. **It is the change detector.** There is no per-keystroke instrumentation, no dirty flags and
 *     no change tracking in the database. A row is "changed" exactly when it differs from the base,
 *     which is the same question the three-way merge already asks.
 *  2. **It is the echo suppressor.** Applying an inbound change advances the base for the affected
 *     rows, so the change that just arrived immediately stops differing from the base and is never
 *     sent back. Without this, two devices each reflecting the other's change is an infinite loop -
 *     the classic failure of naive bidirectional sync.
 *
 * A row with an *unresolved conflict* is withheld from both sides of that equation: it is neither
 * emitted nor allowed to advance the base. It is the one row about which the two devices have
 * genuinely disagreed, and re-sending it would either re-report the conflict forever or silently
 * settle it in favour of whichever device synced last.
 */
object ChangeCursors {

    /**
     * The ops a peer needs to reach [current] from [base].
     *
     * @param base last state both devices agreed on, or null on a first sync.
     * @param blocked row identities frozen by an open conflict, from [conflictRows]. Empty when
     *   there are no conflicts, which is the normal case.
     * @return an empty list when both sides already agree, or a single [SnapshotOp] when there is no
     *   base to diff against - a first sync has nothing to be incremental about.
     */
    fun diff(
        base: InventoryDocument?,
        current: InventoryDocument,
        blocked: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<SyncOp> {
        // No shared history means every row would look like a change, and the peer has no base to
        // merge against either. Sending the whole document is both smaller and the only thing the
        // peer can use.
        if (base == null) {
            return listOf(SnapshotOp(ExportImportManager.buildDocument(current.containers, current.items), current))
        }

        val ops = mutableListOf<SyncOp>()

        val baseById = indexContainers(base)
        val currentById = indexContainers(current)

        // Containers the base had and we no longer do. Emitted as a deletion so the peer removes it;
        // an omission would be read as "no opinion" and the container would come back.
        for ((id, _) in baseById) {
            if (!currentById.containsKey(id) && !blocked.contains(rowIdOfContainer(id))) {
                ops += DeleteContainerOp(id, now)
            }
        }

        for (container in current.containers) {
            val id = container.id.trim()

            if (blocked.contains(rowIdOfContainer(id))) {
                continue
            }

            val baseContainer = baseById[id]

            if (baseContainer == null || containerMetadataDiffers(baseContainer, container)) {
                // Rows deliberately omitted: they travel as their own ops so a removal is expressible
                // as a removal rather than as an absence.
                ops += UpsertContainerOp(container)
            }

            val baseItems = if (baseContainer == null) {
                emptyMap()
            } else {
                InventoryMerger.indexByKey(id, base.itemsOf(id))
            }
            val currentItems = InventoryMerger.indexByKey(id, current.itemsOf(id))

            for ((_, item) in currentItems) {
                val uuid = item.uuid.trim()

                if (blocked.contains(rowIdOf(id, uuid))) {
                    continue
                }

                val baseItem = baseItems[itemKeyOf(id, item)]
                if (baseItem == null || !sameRowContent(baseItem, item)) {
                    ops += UpsertItemOp(id, item)
                }
            }

            // Rows the base had and this container no longer does.
            for ((key, baseItem) in baseItems) {
                if (currentItems.containsKey(key) || blocked.contains(rowIdOf(id, baseItem.uuid))) {
                    continue
                }

                ops += DeleteItemOp(
                    containerId = id,
                    itemUuid = baseItem.uuid,
                    keyKind = key.kind,
                    keyValue = key.value,
                    itemName = baseItem.name,
                    at = now,
                )
            }
        }

        return ops
    }

    /**
     * Identities of the rows frozen by open conflicts, for [diff]'s blocked set.
     *
     * A conflict with an empty [ItemConflict.itemUuid] is a container-level disagreement (its name
     * or location), so it blocks the container itself. Row conflicts block the row.
     */
    fun conflictRows(conflicts: List<ItemConflict>): Set<String> {
        val rows = mutableSetOf<String>()
        for (conflict in conflicts) {
            rows += if (conflict.itemUuid.isBlank()) {
                rowIdOfContainer(conflict.containerId)
            } else {
                rowIdOf(conflict.containerId, conflict.itemUuid)
            }
        }
        return rows
    }

    /** Container identity in the blocked set, mirroring the desktop. */
    private fun rowIdOfContainer(containerId: String): String = containerId.trim()

    private fun rowIdOf(containerId: String, itemUuid: String): String =
        containerId.trim() + com.example.scantron.data.ROW_ID_SEPARATOR + itemUuid.trim()

    /**
     * Compares only the fields a container owns.
     *
     * A `data class` comparison would include the container's timestamp, and the timestamp moves on
     * every merge that touches any row inside it - so a container whose rows had been reconciled
     * would report itself as changed on every single sync, emitting one spurious upsert per
     * container forever.
     */
    private fun containerMetadataDiffers(left: Container, right: Container): Boolean =
        left.name != right.name || left.location != right.location || left.notes != right.notes

    /**
     * True when two rows hold the same content.
     *
     * Row ids are compared never, because they are device-local; `updatedAt` is compared because a
     * re-stamp is a real change the peer should see.
     */
    private fun sameRowContent(left: ContainerItem, right: ContainerItem): Boolean =
        left.containerId.trim() == right.containerId.trim() &&
            left.uuid.trim() == right.uuid.trim() &&
            left.name == right.name &&
            left.barcode == right.barcode &&
            left.quantity == right.quantity &&
            left.category == right.category &&
            left.notes == right.notes &&
            left.updatedAt == right.updatedAt

    private fun indexContainers(document: InventoryDocument): Map<String, Container> {
        val index = LinkedHashMap<String, Container>()
        for (container in document.containers) {
            val id = container.id.trim()
            if (id.isNotEmpty()) index[id] = container
        }
        return index
    }

    /** Exposed for tests pinning the key scheme. */
    internal fun keyOf(containerId: String, item: ContainerItem): ItemKey = itemKeyOf(containerId, item)
}
