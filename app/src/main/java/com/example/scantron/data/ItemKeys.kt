package com.example.scantron.data

/**
 * How a row is keyed for merge and sync purposes.
 *
 * Mirrors the desktop's `ItemKeyResolver.ItemKeyKind` case for case - including the *wire* names,
 * because [name] is what a `deleteItem` op carries and the two codebases must agree on the exact
 * spelling.
 */
enum class ItemKeyKind {
    /** Matched on [ContainerItem.uuid]. The reliable path, available from document version 1.1. */
    Uuid,

    /** Matched on container + barcode, mirroring [ItemDao.getItemByBarcode]. Legacy 1.0 rows. */
    ContainerBarcode,

    /** Matched on container + lowercased trimmed name, against barcode-less rows only. */
    NameOnly,
}

/** A stable, comparable key for one item row. */
data class ItemKey(val kind: ItemKeyKind, val value: String) {
    override fun toString(): String = "$kind:$value"
}

/**
 * Separator between key parts.
 *
 * NUL, because container tags and barcodes are operator-supplied free text: a printable separator
 * could appear inside a tag or a barcode and let two genuinely different (container,
 * discriminator) pairs collapse into one key, silently merging unrelated items. NUL cannot appear
 * in JSON text, so the value is also safe to carry on the wire.
 */
const val ITEM_KEY_SEPARATOR: Char = '\u0000'

/**
 * Separator between the container and the row in a blocked-set entry.
 *
 * A *different* control character from [ITEM_KEY_SEPARATOR] on purpose: the blocked set is keyed by
 * uuid while the merge key may be a composed pair, and sharing one separator would let a composed
 * key collide with a uuid entry.
 */
const val ROW_ID_SEPARATOR: Char = '\u0001'

/**
 * The key for [item] as it sits in [containerId], mirroring the desktop's `ItemKeyResolver.Resolve`
 * and therefore the handheld's own `addItemMerging` rules (barcode first, then name-only).
 */
fun itemKeyOf(containerId: String, item: ContainerItem): ItemKey {
    val uuid = item.uuid.trim()
    if (uuid.isNotEmpty()) {
        return ItemKey(ItemKeyKind.Uuid, uuid)
    }

    val barcode = item.barcode.trim()
    if (barcode.isNotEmpty()) {
        return ItemKey(ItemKeyKind.ContainerBarcode, composeKey(containerId, barcode))
    }

    // Restricted to barcode-less rows, exactly as `getNameOnlyItemByName` is: an item that already
    // carries a barcode is identified by that barcode, and matching it by name could silently
    // conflate two different things.
    return ItemKey(ItemKeyKind.NameOnly, composeKey(containerId, item.name.trim().lowercase()))
}

/**
 * Stable identity of one row for the "frozen by an open conflict" set, mirroring the desktop's
 * `ChangeCursors.RowId`.
 */
fun rowIdOf(containerId: String, itemUuid: String): String =
    containerId.trim() + ROW_ID_SEPARATOR + itemUuid.trim()

/** Container-level conflicts are keyed on the container tag alone, as the desktop does. */
fun rowIdOfContainer(containerId: String): String = containerId.trim()

private fun composeKey(containerId: String, discriminator: String): String =
    containerId.trim() + ITEM_KEY_SEPARATOR + discriminator.trim()
