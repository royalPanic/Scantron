package com.example.scantron.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class ContainerRepository(
    private val containerDao: ContainerDao,
    private val itemDao: ItemDao,
) {
    val allContainers: Flow<List<Container>> = containerDao.getAllContainers()

    suspend fun getAllContainersDirect(): List<Container> = allContainers.first()

    suspend fun getItemsForContainerDirect(containerId: String): List<ContainerItem> = getItemsForContainer(containerId).first()

    suspend fun clearAllData() {
        itemDao.deleteAllItems()
        containerDao.deleteAllContainers()
    }

    /**
         * Replaces all rows in one go after an import.
         *
         * Every incoming item must carry a distinct uuid before it lands, because this path
         * bypasses [saveItem]'s mint-on-insert. Items from a 1.0 file have none, so they are
         * assigned here; items that arrive with a uuid keep theirs, which is what makes a
         * desktop edit and a handheld edit resolve to the same row on the next sync.
         *
         * Duplicate uuids in a single document would make two rows claim one identity, so they
         * are re-minted rather than trusted.
         */
        suspend fun importAll(containers: List<Container>, items: List<ContainerItem>) {
            containerDao.insertAllContainers(containers)
            itemDao.insertAllItems(assignMissingItemUuids(items))
        }

        private fun assignMissingItemUuids(items: List<ContainerItem>): List<ContainerItem> {
            val seen = mutableSetOf<String>()
            return items.map { item ->
                val trimmed = item.uuid.trim()
                if (trimmed.isNotEmpty() && seen.add(trimmed)) {
                    item
                } else {
                    item.copy(uuid = UUID.randomUUID().toString())
                }
            }
        }

    fun getContainer(containerId: String): Flow<Container?> =
        containerDao.getContainerByIdFlow(containerId)

    suspend fun getContainerDirect(containerId: String): Container? =
        containerDao.getContainerById(containerId)

    fun getItemsForContainer(containerId: String): Flow<List<ContainerItem>> =
        itemDao.getItemsForContainer(containerId)

    /**
     * Total units in a container, summing each item's quantity.
     *
     * Prefer this over the row count: a container holding one line of "Screws, qty 12" holds
     * twelve screws, not one item. Distinct-row counts belong to the detail screen, which
     * presents both figures.
     */
    fun getTotalQuantity(containerId: String): Flow<Int> =
        itemDao.getTotalQuantityForContainer(containerId)

    suspend fun createOrUpdateContainer(container: Container) {
        containerDao.insertContainer(container)
    }

    suspend fun deleteContainer(container: Container) {
        containerDao.deleteContainer(container)
    }

    /**
         * Inserts a new item, or updates an existing one by row id.
         *
         * Identity is minted only for genuinely new rows. An update must never overwrite an
         * existing row's uuid: doing so would silently change which item that row is, breaking
         * the export/import match against edits made on a desktop. A caller that reaches the
         * update branch with a blank uuid is therefore falling back to the row's stored identity
         * rather than erasing it.
         *
         * @return the row as persisted, so the caller sees any uuid minted on the way in.
         */
        suspend fun saveItem(item: ContainerItem): ContainerItem {
            if (item.id == 0L) {
                // Mint here rather than letting the caller guess, so the returned row is the row that
                // actually landed. Returning the caller's copy would hand back a blank uuid for an item
                // the database now tracks under a real one.
                val stamped = item.copy(uuid = item.uuid.ifBlank { UUID.randomUUID().toString() })
                itemDao.insertItem(stamped)
                return stamped
            }

            if (item.uuid.isBlank()) {
                val preserved = item.copy(uuid = itemDao.getUuidById(item.id).orEmpty())
                itemDao.updateItem(preserved)
                return preserved
            }

            itemDao.updateItem(item)
            return item
        }

    /** Finds an existing item of [containerId] carrying [barcode], ignoring case and padding. */
    suspend fun getItemByBarcode(containerId: String, barcode: String): ContainerItem? =
        itemDao.getItemByBarcode(containerId, barcode)

    /** Finds a barcode-less item of [containerId] named [name], ignoring case and padding. */
    suspend fun getNameOnlyItemByName(containerId: String, name: String): ContainerItem? =
        itemDao.getNameOnlyItemByName(containerId, name)

    /**
     * Adds [item] to its container, folding it into a row that already represents the same thing
     * instead of creating a duplicate.
     *
     * Two matches are tried, in order:
     *  1. an item already carrying the same barcode;
     *  2. an item with **no barcode** whose name matches - this is what lets name-only items,
     *     which have nothing scannable to identify them by, accumulate when the user types the
     *     name again via the Add Item dialog.
     *
     * On a match the existing row's quantity is increased by [item]'s and the row is written
     * back, so its id, name, category and notes survive. An item that already carries a barcode
     * is deliberately never matched by name: that barcode is its identity, and merging two
     * different barcodes into it would silently conflate them.
     *
     * Returns the row as it now stands.
     */
    suspend fun addItemMerging(item: ContainerItem): ContainerItem {
        val existing = if (item.barcode.isNotBlank()) {
            getItemByBarcode(item.containerId, item.barcode)
        } else {
            getNameOnlyItemByName(item.containerId, item.name)
        } ?: run {
                    // saveItem returns the row as persisted, including any uuid it minted, so the caller
                    // never receives an identity the database did not actually store.
                    return saveItem(item)
                }

        val merged = existing.copy(
            quantity = existing.quantity + item.quantity,
            updatedAt = System.currentTimeMillis(),
        )
        saveItem(merged)
        return merged
    }

    suspend fun deleteItem(item: ContainerItem) {
        itemDao.deleteItem(item)
    }

    fun searchItems(query: String): Flow<List<ItemWithContainer>> {
        return if (query.isBlank()) {
            itemDao.getAllItemsWithContainers()
        } else {
            itemDao.searchItemsAcrossContainers(query)
        }
    }

    suspend fun seedSampleDataIfEmpty() {
        // Pre-populate demo containers if none exist
        val existing = containerDao.getContainerById("BOX-101")
        if (existing == null) {
            val c1 = Container("BOX-101", "Power Tools & Hardware", "Garage Shelf 2-A", "Heavy plastic storage bin")
            val c2 = Container("BIN-42", "Camping Gear", "Attic Rack 1", "Waterproof green container")
            val c3 = Container("DRAWER-03", "Office Supplies", "Study Room Desk", "Top left drawer")

            containerDao.insertContainer(c1)
            containerDao.insertContainer(c2)
            containerDao.insertContainer(c3)

            itemDao.insertItem(ContainerItem(containerId = "BOX-101", name = "Cordless Drill 18V", barcode = "088381699921", quantity = 1, category = "Tools", notes = "Includes 2 lithium batteries"))
            itemDao.insertItem(ContainerItem(containerId = "BOX-101", name = "Box of Drywall Screws (2 inch)", barcode = "076808001021", quantity = 3, category = "Hardware", notes = "100 pcs per box"))
            itemDao.insertItem(ContainerItem(containerId = "BOX-101", name = "Measuring Tape 25ft", barcode = "037103248881", quantity = 2, category = "Tools", notes = "Rubberized grip"))

            itemDao.insertItem(ContainerItem(containerId = "BIN-42", name = "2-Person Camping Tent", barcode = "076501065123", quantity = 1, category = "Outdoors", notes = "Green Coleman tent with rainfly"))
            itemDao.insertItem(ContainerItem(containerId = "BIN-42", name = "LED Headlamp", barcode = "084251210045", quantity = 2, category = "Electronics", notes = "Rechargeable USB-C"))
            itemDao.insertItem(ContainerItem(containerId = "BIN-42", name = "Sleeping Bag 30°F", barcode = "076501112234", quantity = 2, category = "Bedding"))

            itemDao.insertItem(ContainerItem(containerId = "DRAWER-03", name = "AA Batteries (8-pack)", barcode = "041333001012", quantity = 4, category = "Electronics"))
            itemDao.insertItem(ContainerItem(containerId = "DRAWER-03", name = "Label Maker Tape Refill", barcode = "012587002034", quantity = 3, category = "Office", notes = "12mm black on white"))
        }
    }
}