package com.example.scantron.data

import kotlinx.coroutines.flow.Flow

class ContainerRepository(
    private val containerDao: ContainerDao,
    private val itemDao: ItemDao
) {
    val allContainers: Flow<List<Container>> = containerDao.getAllContainers()

    fun getContainer(containerId: String): Flow<Container?> =
        containerDao.getContainerByIdFlow(containerId)

    suspend fun getContainerDirect(containerId: String): Container? =
        containerDao.getContainerById(containerId)

    fun getItemsForContainer(containerId: String): Flow<List<ContainerItem>> =
        itemDao.getItemsForContainer(containerId)

    fun getItemCount(containerId: String): Flow<Int> =
        itemDao.getItemCountForContainer(containerId)

    suspend fun createOrUpdateContainer(container: Container) {
        containerDao.insertContainer(container)
    }

    suspend fun deleteContainer(container: Container) {
        containerDao.deleteContainer(container)
    }

    suspend fun saveItem(item: ContainerItem) {
        if (item.id == 0L) {
            itemDao.insertItem(item)
        } else {
            itemDao.updateItem(item)
        }
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

    fun searchContainers(query: String): Flow<List<Container>> =
        containerDao.searchContainers(query)

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
