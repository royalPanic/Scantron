package com.example.scantron.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor

/**
 * Cross-device acceptance check: imports a file produced by the *desktop writer*
 * (Scantron.Core's InventoryReader.Write) and asserts the device lands the same rows.
 *
 * The file is checked in rather than generated at test time so this runs without a .NET
 * toolchain. Regenerate with the desktop writer before changing the contract.
 */
@RunWith(RobolectricTestRunner::class)
class DesktopWireCompatibilityTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ContainerRepository
    private lateinit var manager: ExportImportManager

    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        repository = ContainerRepository(database.containerDao(), database.itemDao())
        manager = ExportImportManager(ApplicationProvider.getApplicationContext(), repository)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun desktopExport(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("desktop_export.json")) {
            "desktop_export.json missing from test resources"
        }.bufferedReader().use { it.readText() }

    private fun importDesktop(): ImportResult = runBlocking {
        val file = File.createTempFile("desktop_export", ".json")
        file.writeText(desktopExport())
        val result = manager.importFromJson(Uri.fromFile(file))
        file.delete()
        result
    }

    @Test
    fun `the device accepts a document written by the desktop`() {
        val result = importDesktop()

        assertTrue(
            "device rejected the desktop export: ${result.message}",
            result.success,
        )
        assertEquals(2, result.containerCount)
        assertEquals(4, result.itemCount)
    }

    @Test
        fun `every desktop field survives the trip to the device`() = runBlocking {
            importDesktop()

            val items = repository.getItemsForContainerDirect("BOX-101")
            val drill = items.single { it.name == "Cordless Drill 18V" }

            assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", drill.uuid)
            assertEquals("088381699921", drill.barcode)
            assertEquals(1, drill.quantity)
            assertEquals("Tools", drill.category)
            assertEquals("Includes 2 lithium batteries", drill.notes)
        }

    @Test
        fun `the device keeps both rows when one uuid arrives twice`() = runBlocking {
        // This is the divergence the desktop IndexByKey fix targets. The device must not
        // collapse them either, and must hand out distinct identities.
        importDesktop()

        val items = repository.getItemsForContainerDirect("BOX-101")
        val shared = items.filter { it.name.startsWith("Shared") }

        assertEquals("both duplicate-uuid rows must survive", 2, shared.size)

        val uuids = items.map { it.uuid }
        assertEquals(
            "no two rows may claim one identity",
            uuids.size,
            uuids.distinct().size,
        )

        // The first occurrence keeps the identity the desktop sent; the other is re-minted.
        assertTrue("collision-uuid must survive on its original row", uuids.contains("collision-uuid"))
    }

    @Test
        fun `a legacy row with no identity is given one on the device`() = runBlocking {
        importDesktop()

        val spare = repository.getItemsForContainerDirect("BOX-101").single { it.name == "Unlabelled spare" }
        assertTrue("device must mint an identity for a 1.0-style row", spare.uuid.isNotBlank())
    }

    @Test
        fun `an empty container is preserved as empty`() = runBlocking {
        importDesktop()

        assertEquals(0, repository.getItemsForContainerDirect("EMPTY-1").size)
    }

    @Test
        fun `the device re-exports rows that still carry an identity`() = runBlocking {
            importDesktop()

            val rebuilt = repository.getAllContainersDirect().flatMap { container ->
                repository.getItemsForContainerDirect(container.id).map { container.id to it }
            }

            assertEquals(4, rebuilt.size)
            assertTrue(
                "every re-exported row must carry an identity",
                rebuilt.all { (_, item) -> item.uuid.isNotBlank() },
            )
        }
    }