package com.example.scantron.sync

import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.InventoryDocument
import com.example.scantron.data.ItemKey
import com.example.scantron.data.itemKeyOf

/**
 * Immutable edits to an [InventoryDocument], used to rebuild a peer's view of the world from the
 * shared base plus a batch of ops.
 *
 * The live-sync receiver does not merge field-by-field on the wire. It reconstructs what the peer
 * now holds - *base + ops* - and then hands that to [InventoryMerger], which already knows the
 * field rules. Doing it this way means a live sync and a USB import cannot drift: they are the same
 * comparison, over the same two documents.
 *
 * A direct port of the desktop's `Sync/SyncDocuments.cs`, including its choice to ignore an unknown
 * field name rather than throw: a newer peer may legitimately know a field this build does not, and
 * dropping the connection over it is worse than ignoring the one field.
 */
object SyncDocuments {

    /** Empty document, used when a peer sends an op with no usable base. */
    val EMPTY: InventoryDocument = InventoryDocument()

    /**
     * Adds or replaces a container, matched on trimmed tag.
     *
     * [rows] replaces the container's rows outright; callers that only have new metadata for the
     * container pass the rows it already holds, because a container upsert on the wire deliberately
     * carries no rows and must not drop the ones the base has.
     */
    fun upsertContainer(
        document: InventoryDocument,
        container: Container,
        rows: List<ContainerItem>,
    ): InventoryDocument {
        val id = container.id.trim()
        val containers = document.containers.toMutableList()
        val index = containers.indexOfFirst { it.id.trim() == id }

        if (index >= 0) {
            containers[index] = container
        } else {
            containers += container
        }

        return InventoryDocument(
            containers = containers,
            items = document.items.filterNot { it.containerId.trim() == id } + rows,
        )
    }

    /** Removes a container and every row inside it. */
    fun removeContainer(document: InventoryDocument, containerId: String): InventoryDocument {
        val id = containerId.trim()
        val remaining = document.containers.filterNot { it.id.trim() == id }

        if (remaining.size == document.containers.size) {
            return document
        }

        return InventoryDocument(
            containers = remaining,
            items = document.items.filterNot { it.containerId.trim() == id },
        )
    }

    /**
     * Adds or replaces one row, matched on the same key the merge uses.
     *
     * Matching on [itemKeyOf] rather than on the uuid alone is what lets a legacy 1.0 row - which
     * has no uuid - be updated instead of duplicated.
     */
    fun upsertItem(
        document: InventoryDocument,
        containerId: String,
        item: ContainerItem,
    ): InventoryDocument {
        val id = containerId.trim()

        // The row must carry its owning container tag; a wire row's tag lives on the op, not on the
        // row, and every later step (the diff, the import) groups by that field.
        val stamped = item.copy(containerId = id)
        val key = itemKeyOf(id, stamped)

        val rows = document.itemsOf(id).toMutableList()
        val index = rows.indexOfFirst { itemKeyOf(id, it) == key }
        if (index >= 0) {
            rows[index] = stamped
        } else {
            rows += stamped
        }

        // An op may name a container this document has never seen - a peer's new container arrives
        // as its own upsert, but an op can be applied out of order by a reconnect - so the container
        // is created rather than letting the row be orphaned.
        val containers = document.containers.toMutableList()
        if (containers.none { it.id.trim() == id }) {
            containers += Container(id = id)
        }

        return InventoryDocument(
            containers = containers,
            items = document.items.filterNot { it.containerId.trim() == id } + rows,
        )
    }

    /** Removes one row, identified by the key the deleting side computed. */
    fun removeItem(
        document: InventoryDocument,
        containerId: String,
        key: ItemKey,
    ): InventoryDocument {
        val id = containerId.trim()
        val existingRows = document.itemsOf(id)
        val index = existingRows.indexOfFirst { itemKeyOf(id, it) == key }

        if (index < 0) {
            return document
        }

        val rows = existingRows.toMutableList().apply { removeAt(index) }
        return InventoryDocument(
            containers = document.containers,
            items = document.items.filterNot { it.containerId.trim() == id } + rows,
        )
    }

    /**
     * Sets one named field on a container. An unknown field name is ignored rather than throwing.
     */
    fun setContainerField(
        document: InventoryDocument,
        containerId: String,
        field: String,
        value: String?,
    ): InventoryDocument {
        val id = containerId.trim()
        val containers = document.containers.toMutableList()
        val index = containers.indexOfFirst { it.id.trim() == id }

        if (index < 0) {
            return document
        }

        val container = containers[index]
        containers[index] = when (field) {
            "Name" -> container.copy(name = value.orEmpty())
            "Location" -> container.copy(location = value.orEmpty())
            "Notes" -> container.copy(notes = value.orEmpty())
            else -> container
        }

        return document.copy(containers = containers)
    }

    /**
     * Sets one named field on a row, found by its key.
     *
     * The row's timestamp is deliberately not advanced: an unresolved conflict must not look like a
     * fresh edit, or the next merge would rank it above the peer's real change.
     */
    fun setItemField(
        document: InventoryDocument,
        containerId: String,
        key: ItemKey,
        field: String,
        value: String?,
    ): InventoryDocument {
        val id = containerId.trim()
        val rows = document.itemsOf(id).toMutableList()
        val index = rows.indexOfFirst { itemKeyOf(id, it) == key }

        if (index < 0) {
            return document
        }

        val item = rows[index]
        rows[index] = when (field) {
            "Name" -> item.copy(name = value.orEmpty())
            "Barcode" -> item.copy(barcode = value.orEmpty())
            "Quantity" -> item.copy(quantity = parseQuantity(value, item.quantity))
            "Category" -> item.copy(category = value.orEmpty())
            "Notes" -> item.copy(notes = value.orEmpty())
            else -> item
        }

        return document.copy(items = document.items.filterNot { it.containerId.trim() == id } + rows)
    }

    /**
     * Parses a quantity from a conflict's text form, falling back to the current value.
     *
     * Values travel as strings because a conflict is a presentation of two disagreeing texts, so the
     * round-trip has to tolerate anything a human could have put in the row. A value that is not a
     * number keeps the row's existing quantity rather than throwing on a sync path.
     */
    private fun parseQuantity(value: String?, fallback: Int): Int =
        value?.trim()?.toIntOrNull() ?: fallback

    /** A canonical ordering of a document, so two equivalent documents compare equal. */
    fun canonical(document: InventoryDocument): InventoryDocument = InventoryDocument(
        containers = document.containers.sortedBy { it.id.trim() },
        items = document.items.sortedWith(compareBy({ it.containerId.trim() }, { it.uuid.trim() }, { it.name })),
    )
}
