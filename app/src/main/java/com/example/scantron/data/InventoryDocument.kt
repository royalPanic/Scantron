package com.example.scantron.data

/**
 * A whole inventory as one value: containers plus their items, with no database behind it.
 *
 * The live-sync feature constantly needs to answer "what do we hold now?", "what did we last
 * agree on?" and "what does the peer hold now?" - three documents, none of which is necessarily
 * the database. Modelling that as a plain immutable value (mirroring the desktop's
 * `InventoryDocument`) keeps the merge, the diff and the change detector pure functions that can
 * be tested without Room, and it deliberately gives items no row id: row ids are device-local and
 * must never travel, so a document is keyed on the same identity rules a merge uses.
 *
 * [items] holds every row for every container. Callers group with [itemsOf] rather than nesting,
 * because a flat list is what the export document, the diff and the repository all produce.
 */
data class InventoryDocument(
    val containers: List<Container> = emptyList(),
    val items: List<ContainerItem> = emptyList(),
) {

    fun container(containerId: String): Container? =
        containers.firstOrNull { it.id.trim() == containerId.trim() }

    fun itemsOf(containerId: String): List<ContainerItem> =
        items.filter { it.containerId.trim() == containerId.trim() }

    /** Row carrying [uuid], or null. Identity is the uuid, so a blank one matches nothing. */
    fun itemByUuid(uuid: String): ContainerItem? {
        val wanted = uuid.trim()
        if (wanted.isEmpty()) return null
        return items.firstOrNull { it.uuid.trim() == wanted }
    }

    /** Row identified by [key] within [containerId], using the same rules a merge uses. */
    fun itemByKey(containerId: String, key: ItemKey): ContainerItem? =
        itemsOf(containerId).firstOrNull { itemKeyOf(containerId, it) == key }

    fun isEmpty(): Boolean = containers.isEmpty() && items.isEmpty()

    /** This document with [document]'s rows for one container replacing what is held now. */
    fun withContainerRows(containerId: String, rows: List<ContainerItem>): InventoryDocument =
        copy(items = items.filterNot { it.containerId.trim() == containerId.trim() } + rows)

    companion object {
        val EMPTY = InventoryDocument()
    }
}
