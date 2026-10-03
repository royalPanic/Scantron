package com.example.scantron.scanner

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.scantron.data.AppDatabase
import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ContainerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * Covers the routing rules that connect hardware scans to containers:
 *
 *  - a code that already identifies a container navigates to it and is never queued as an item
 *  - anything else is queued against the open container, or prompts for one when none is open
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScanSessionViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ContainerRepository

    /** Room executors run inline so the assertions below can observe completed writes. */
    private val directExecutor = Executor { it.run() }

    private fun scanEvent(data: String) = ScanEvent(data = data, codeId = "C", aimId = "]C1")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        repository = ContainerRepository(database.containerDao(), database.itemDao())
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    // ---- container codes navigate, they are never items ------------------------------

    @Test
    fun `scanning an existing container opens it without prompting`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Tools"))
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        vm.onActiveContainerChanged(null)
        vm.onScan(scanEvent("BOX-101")) { navigatedTo = it }

        assertEquals("BOX-101", navigatedTo)
        assertNull(vm.containerPrompt.value)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `scanning an existing container from the containers list does not prompt`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        // No container open: we are on the containers list / search screen.
        vm.onActiveContainerChanged(null)
        vm.onScan(scanEvent("bin-42")) { navigatedTo = it }

        assertEquals("BIN-42", navigatedTo)
        assertNull(vm.containerPrompt.value)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `scanning a different container while one is open navigates instead of queueing`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("BIN-42")) { navigatedTo = it }

        assertEquals("BIN-42", navigatedTo)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `re-scanning the container that is already open does nothing`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("BOX-101")) { navigatedTo = it }

        assertNull(navigatedTo)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `a container can never be queued as an item of itself`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("BOX-101")) {}
        vm.onContainerResolved("BOX-101") {}

        assertTrue(vm.pendingScans.value.isEmpty())
    }

    // ---- non-container codes ----------------------------------------------------------

    @Test
    fun `scan with no open container raises a container prompt`() = runBlocking {
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged(null)
        vm.onScan(scanEvent("088381699921")) {}

        assertEquals("088381699921", vm.containerPrompt.value?.data)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `scan with an open container is queued without prompting`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("088381699921")) {}

        assertNull(vm.containerPrompt.value)
        assertEquals(1, vm.pendingScans.value.size)
        assertEquals("BOX-101", vm.pendingScans.value.single().containerId)
    }

    @Test
    fun `assigning a scanned item to a new container keeps the item queued`() = runBlocking {
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        vm.onScan(scanEvent("088381699921")) {}
        // The user renames the container, so the scan is an item that belongs inside it.
        vm.onContainerResolved("BOX-101") { navigatedTo = it }

        assertEquals("BOX-101", navigatedTo)
        assertNotNull(repository.getContainerDirect("BOX-101"))
        assertEquals("BOX-101", vm.pendingScans.value.single().containerId)
        assertEquals("088381699921", vm.pendingScans.value.single().event.data)
    }

    @Test
    fun `creating a container from the scanned code does not add it as an item`() = runBlocking {
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        vm.onScan(scanEvent("BOX-101")) {}
        vm.onContainerCreated(Container(id = "BOX-101", name = "Tools")) { navigatedTo = it }

        assertEquals("BOX-101", navigatedTo)
        assertNotNull(repository.getContainerDirect("BOX-101"))
        assertTrue(
            "The container tag must not be queued as an item of itself",
            vm.pendingScans.value.isEmpty(),
        )
    }

    @Test
    fun `resolving the prompt against an existing container does not overwrite it`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BIN-42", name = "Camping Gear"))
        val vm = ScanSessionViewModel(repository)

        vm.onScan(scanEvent("084251210045")) {}
        vm.onContainerResolved("BIN-42") {}

        assertEquals("Camping Gear", repository.getContainerDirect("BIN-42")?.name)
        assertEquals("BIN-42", vm.pendingScans.value.single().containerId)
    }

    @Test
    fun `creating a container from the prompt saves its metadata`() = runBlocking {
        val vm = ScanSessionViewModel(repository)
        var navigatedTo: String? = null

        vm.onScan(scanEvent("088381699921")) {}
        vm.onContainerCreated(
            Container(id = "BOX-101", name = " Tools ", location = " Shelf A ", notes = " note "),
        ) { navigatedTo = it }

        val saved = repository.getContainerDirect("BOX-101")
        assertNotNull(saved)
        assertEquals("Tools", saved?.name)
        assertEquals("Shelf A", saved?.location)
        assertEquals("note", saved?.notes)
        assertEquals("BOX-101", navigatedTo)
    }

    @Test
    fun `dismissing the prompt drops the scan`() = runBlocking {
        val vm = ScanSessionViewModel(repository)

        vm.onScan(scanEvent("088381699921")) {}
        vm.dismissContainerPrompt()

        assertNull(vm.containerPrompt.value)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    // ---- queue management --------------------------------------------------------------

    @Test
    fun `committing writes items and clears the queue`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)
        var committed = -1

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("088381699921")) {}
        vm.onScan(scanEvent("076808001021")) {}
        vm.commitPendingScans("BOX-101") { committed = it }

        assertEquals(2, committed)
        assertTrue(vm.pendingScans.value.isEmpty())

        val items = repository.getItemsForContainerDirect("BOX-101")
        assertEquals(2, items.size)
        assertEquals(setOf("088381699921", "076808001021"), items.map { it.barcode }.toSet())
        assertTrue(items.all { it.name == it.barcode && it.quantity == 1 })
    }

    // ---- repeat scans merge into one entry with a count --------------------------------

    @Test
    fun `scanning the same code repeatedly merges into one entry`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("AAA")) {}

        val queued = vm.pendingScans.value
        assertEquals(1, queued.size)
        assertEquals("AAA", queued.single().event.data)
        assertEquals(3, queued.single().count)
    }

    @Test
    fun `different codes stay separate and each keep their own count`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("BBB")) {}
        vm.onScan(scanEvent("AAA")) {}

        val queued = vm.pendingScans.value
        assertEquals(listOf("AAA" to 2, "BBB" to 1), queued.map { it.event.data to it.count })
    }

    @Test
    fun `merging ignores case and surrounding whitespace`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("abc")) {}
        vm.onScan(scanEvent("  ABC  ")) {}

        val queued = vm.pendingScans.value
        assertEquals(1, queued.size)
        assertEquals(2, queued.single().count)
        // The first-seen value is the one displayed and stored.
        assertEquals("abc", queued.single().event.data)
    }

    // ---- pre-existing items in the database are never overwritten ----------------------

    @Test
    fun `committing a scanned code that already exists adds to its quantity`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val original = ContainerItem(
            containerId = "BOX-101",
            name = "Cordless Drill",
            barcode = "088381699921",
            quantity = 2,
            category = "Tools",
            notes = "keep me",
        )
        repository.saveItem(original)
        val originalId = repository.getItemsForContainerDirect("BOX-101").single().id

        val vm = ScanSessionViewModel(repository)
        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("088381699921")) {}
        vm.onScan(scanEvent("088381699921")) {}
        vm.commitPendingScans("BOX-101") {}

        val items = repository.getItemsForContainerDirect("BOX-101")
        // One row for the barcode, not two.
        assertEquals(1, items.size)
        val merged = items.single()
        // The existing row is updated in place, keeping its identity and metadata.
        assertEquals(originalId, merged.id)
        assertEquals("Cordless Drill", merged.name)
        assertEquals("Tools", merged.category)
        assertEquals("keep me", merged.notes)
        // 2 already in stock + 2 scanned.
        assertEquals(4, merged.quantity)
    }

    @Test
    fun `scanning a new code does not disturb unrelated existing items`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.saveItem(
            ContainerItem(
                containerId = "BOX-101",
                name = "Screws",
                barcode = "076808001021",
                quantity = 5,
            )
        )
        val before = repository.getItemsForContainerDirect("BOX-101")

        val vm = ScanSessionViewModel(repository)
        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("084251210045")) {}
        vm.commitPendingScans("BOX-101") {}

        val after = repository.getItemsForContainerDirect("BOX-101")
        assertEquals(2, after.size)
        val untouched = after.single { it.barcode == "076808001021" }
        assertEquals(before.single().id, untouched.id)
        assertEquals(5, untouched.quantity)
        assertEquals("Screws", untouched.name)
    }

    @Test
    fun `same barcode in a different container is a separate item`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        repository.saveItem(
            ContainerItem(containerId = "BOX-101", name = "Drill", barcode = "AAA", quantity = 2)
        )

        val vm = ScanSessionViewModel(repository)
        vm.onActiveContainerChanged("BIN-42")
        vm.onScan(scanEvent("AAA")) {}
        vm.commitPendingScans("BIN-42") {}

        assertEquals(2, repository.getItemByBarcode("BOX-101", "AAA")?.quantity)
        assertEquals(1, repository.getItemByBarcode("BIN-42", "AAA")?.quantity)
    }

    // ---- name-only items (manually added, no barcode) ---------------------------------
    //
    // Realistically these are entered through the Add Item dialog, since a name-only item has
    // nothing scannable that identifies it. The scan-path cases below are retained because the
    // same merge rule backs both entry points.

    @Test
    fun `adding a name-only item twice accumulates into one row`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val first = ContainerItem(
            containerId = "BOX-101",
            name = "Measuring Tape 25ft",
            barcode = "",
            quantity = 1,
        )
        repository.addItemMerging(first)

        val merged = repository.addItemMerging(
            ContainerItem(
                containerId = "BOX-101",
                name = "Measuring Tape 25ft",
                barcode = "",
                quantity = 2,
            )
        )

        val items = repository.getItemsForContainerDirect("BOX-101")
        assertEquals(1, items.size)
        // The caller's `first` copy still carries id 0; the stored row is what was merged into.
        val stored = items.single()
        assertEquals(stored.id, merged.id)
        assertEquals(3, merged.quantity)
    }

    @Test
    fun `adding a name-only item ignores case and surrounding whitespace`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Measuring Tape", barcode = "", quantity = 1)
        )

        val merged = repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "  measuring tape  ", barcode = "", quantity = 4)
        )

        assertEquals(5, merged.quantity)
        assertEquals(1, repository.getItemsForContainerDirect("BOX-101").size)
    }

    @Test
    fun `adding a name-only item preserves its category and notes`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.addItemMerging(
            ContainerItem(
                containerId = "BOX-101",
                name = "Screws",
                barcode = "",
                quantity = 1,
                category = "Hardware",
                notes = "2 inch",
            )
        )

        val merged = repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Screws", barcode = "", quantity = 1)
        )

        assertEquals("Hardware", merged.category)
        assertEquals("2 inch", merged.notes)
        assertEquals(2, merged.quantity)
    }

    @Test
    fun `an item that has its own barcode is not merged by name`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val barcoded = ContainerItem(
            containerId = "BOX-101",
            name = "Cordless Drill",
            barcode = "999",
            quantity = 3,
        )
        repository.addItemMerging(barcoded)

        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Cordless Drill", barcode = "", quantity = 1)
        )

        val items = repository.getItemsForContainerDirect("BOX-101")
        assertEquals(2, items.size)
        assertEquals(3, items.single { it.barcode == "999" }.quantity)
        assertEquals(1, items.single { it.barcode.isBlank() }.quantity)
    }

    @Test
    fun `barcode match wins over name-only match`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Old", barcode = "AAA", quantity = 2)
        )
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "AAA", barcode = "", quantity = 5)
        )

        val merged = repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "AAA", barcode = "AAA", quantity = 1)
        )

        assertEquals(3, merged.quantity)
        assertEquals("Old", merged.name)
        assertEquals(5, repository.getNameOnlyItemByName("BOX-101", "AAA")?.quantity)
    }

    @Test
    fun `different name-only items stay separate`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Screws", barcode = "", quantity = 1)
        )
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Nails", barcode = "", quantity = 1)
        )

        assertEquals(2, repository.getItemsForContainerDirect("BOX-101").size)
    }

    @Test
    fun `the same name-only item in different containers stays separate`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Drill", barcode = "", quantity = 1)
        )

        val merged = repository.addItemMerging(
            ContainerItem(containerId = "BIN-42", name = "Drill", barcode = "", quantity = 1)
        )

        assertEquals(1, repository.getNameOnlyItemByName("BOX-101", "Drill")?.quantity)
        assertEquals(1, merged.quantity)
        assertEquals(2, repository.getAllContainersDirect().size)
    }

    @Test
    fun `a scanned code is never absorbed by a name-only item`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.addItemMerging(
            ContainerItem(containerId = "BOX-101", name = "Cordless Drill", barcode = "", quantity = 1)
        )

        val vm = ScanSessionViewModel(repository)
        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("Cordless Drill")) {}
        vm.onScan(scanEvent("Cordless Drill")) {}
        vm.commitPendingScans("BOX-101") {}

        val items = repository.getItemsForContainerDirect("BOX-101")
        // A scanned code is a barcode, so it never matches the name-only fallback: it becomes
        // its own row carrying that code. The name-only item is left alone.
        assertEquals(2, items.size)
        assertEquals(1, items.single { it.barcode.isBlank() }.quantity)
        val scanned = items.single { it.barcode == "Cordless Drill" }
        assertEquals(2, scanned.quantity)
    }

    @Test
    fun `the same code queued for different containers does not merge`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onActiveContainerChanged("BIN-42")
        vm.onScan(scanEvent("AAA")) {}

        val queued = vm.pendingScans.value
        assertEquals(2, queued.size)
        assertTrue(queued.all { it.count == 1 })
        assertEquals(
            setOf("BOX-101", "BIN-42"),
            queued.map { it.containerId }.toSet(),
        )
    }

    @Test
    fun `commit turns the count into the item quantity`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)
        var committed = -1

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("BBB")) {}
        vm.commitPendingScans("BOX-101") { committed = it }

        // The callback reports total units, not rows.
        assertEquals(3, committed)
        assertTrue(vm.pendingScans.value.isEmpty())

        val items = repository.getItemsForContainerDirect("BOX-101")
        assertEquals("repeat scans must not create duplicate rows", 2, items.size)
        assertEquals(2, items.single { it.barcode == "AAA" }.quantity)
        assertEquals(1, items.single { it.barcode == "BBB" }.quantity)
    }

    @Test
    fun `discarding a merged entry removes all of its scans`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("AAA")) {}
        val mergedId = vm.pendingScans.value.single().id

        vm.discardPendingScan(mergedId)
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `discarding removes individual and bulk pending scans`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onScan(scanEvent("BBB")) {}
        val firstId = vm.pendingScans.value.first().id

        vm.discardPendingScan(firstId)
        assertEquals(listOf("BBB"), vm.pendingScans.value.map { it.event.data })

        vm.discardAllPendingScans("BOX-101")
        assertTrue(vm.pendingScans.value.isEmpty())
    }

    @Test
    fun `pending scans are scoped to their container`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        val vm = ScanSessionViewModel(repository)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(scanEvent("AAA")) {}
        vm.onActiveContainerChanged("BIN-42")
        vm.onScan(scanEvent("BBB")) {}

        assertEquals(
            listOf("AAA" to "BOX-101", "BBB" to "BIN-42"),
            vm.pendingScans.value.map { it.event.data to it.containerId },
        )
    }

    @Test
    fun `blank scans are ignored`() = runBlocking {
        val vm = ScanSessionViewModel(repository)

        vm.onScan(ScanEvent(data = "   ")) {}
        assertNull(vm.containerPrompt.value)

        vm.onActiveContainerChanged("BOX-101")
        vm.onScan(ScanEvent(data = "")) {}
        assertTrue(vm.pendingScans.value.isEmpty())
    }
}
