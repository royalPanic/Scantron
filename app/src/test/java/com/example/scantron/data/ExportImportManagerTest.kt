package com.example.scantron.data

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor

/**
 * The export/import document is the contract between the handheld and any desktop tool
 * reading the same files. Every rejection rule here is one the desktop writer has to
 * reproduce, so these tests double as the executable specification for that validator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExportImportManagerTest {

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

    private fun importJson(json: String): ImportResult = runBlocking {
        val file = File.createTempFile("scantron_import", ".json")
        file.writeText(json)
        val uri = Uri.fromFile(file)
        val result = manager.importFromJson(uri)
        file.delete()
        result
    }

    private fun itemJson(
        name: String = "Cordless Drill 18V",
        barcode: String = "088381699921",
        quantity: Int = 1,
        category: String = "Tools",
        notes: String = "Includes 2 lithium batteries",
        uuid: String? = "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
        updatedAt: Long = 1705318200000,
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("barcode", barcode)
        put("quantity", quantity)
        put("category", category)
        put("notes", notes)
        uuid?.let { put("uuid", it) }
        put("updatedAt", updatedAt)
    }

    private fun containerJson(id: String, vararg items: JSONObject): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", "Power Tools & Hardware")
        put("location", "Garage Shelf 2-A")
        put("notes", "Heavy plastic storage bin")
        put("updatedAt", 1705318200000)
        put("items", JSONArray().apply { items.forEach { put(it) } })
    }

    private fun document(
        version: String = "1.1",
        vararg containers: JSONObject,
    ): String = JSONObject().apply {
        put("app", "Scantron")
        put("version", version)
        put("exportedAt", "2026-10-04T10:30:00Z")
        put("containers", JSONArray().apply { containers.forEach { put(it) } })
    }.toString()

    @Test
    fun `a 1_1 document imports and keeps item identity`() = runBlocking {
        val result = importJson(document("1.1", containerJson("BOX-101", itemJson(uuid = "abc-123"))))

        assertTrue(result.success)
        assertEquals(1, result.containerCount)
        assertEquals("abc-123", repository.getItemsForContainerDirect("BOX-101").single().uuid)
    }

    @Test
    fun `a 1_0 document still imports and mints an identity`() = runBlocking {
        val result = importJson(document("1.0", containerJson("BOX-101", itemJson(uuid = null))))

        assertTrue(result.success)
        // A 1.0 file has no uuid, so the row must not land without one.
        assertTrue(repository.getItemsForContainerDirect("BOX-101").single().uuid.isNotBlank())
    }

    @Test
    fun `a 1_1 document missing uuid still lands with an identity`() = runBlocking {
        val result = importJson(document("1.1", containerJson("BOX-101", itemJson(uuid = null))))

        assertTrue(result.success)
        assertTrue(repository.getItemsForContainerDirect("BOX-101").single().uuid.isNotBlank())
    }

    @Test
    fun `two items without uuids receive distinct identities`() = runBlocking {
        val result = importJson(
            document(
                "1.0",
                containerJson(
                    "BOX-101",
                    itemJson(name = "Drill", uuid = null),
                    itemJson(name = "Screws", barcode = "076808001021", uuid = null),
                ),
            ),
        )

        assertTrue(result.success)
        val uuids = repository.getItemsForContainerDirect("BOX-101").map { it.uuid }
        assertEquals(2, uuids.size)
        assertNotEquals(uuids[0], uuids[1])
    }

    @Test
    fun `duplicate uuids in one document are separated`() = runBlocking {
        val result = importJson(
            document(
                "1.1",
                containerJson(
                    "BOX-101",
                    itemJson(name = "Drill", uuid = "same-uuid"),
                    itemJson(name = "Screws", barcode = "076808001021", uuid = "same-uuid"),
                ),
            ),
        )

        assertTrue(result.success)
        val uuids = repository.getItemsForContainerDirect("BOX-101").map { it.uuid }
        assertNotEquals(uuids[0], uuids[1])
    }

    @Test
    fun `an unsupported version is rejected`() {
        val result = importJson(document("2.0", containerJson("BOX-101", itemJson())))

        assertTrue(!result.success)
        assertTrue(result.message.contains("Unsupported export version"))
    }

    @Test
    fun `a missing containers array is rejected`() {
        val result = importJson(JSONObject().put("app", "Scantron").put("version", "1.1").toString())

        assertTrue(!result.success)
    }

    @Test
    fun `a blank container id is rejected`() {
        val result = importJson(document("1.1", containerJson("   ", itemJson())))

        assertTrue(!result.success)
    }

    @Test
    fun `a duplicate container id is rejected`() {
        val result = importJson(document("1.1", containerJson("BOX-101", itemJson()), containerJson("BOX-101", itemJson())))

        assertTrue(!result.success)
        assertTrue(result.message.contains("Duplicate container tag"))
    }

    @Test
    fun `a blank item name is rejected`() {
        val result = importJson(document("1.1", containerJson("BOX-101", itemJson(name = "  "))))

        assertTrue(!result.success)
    }

    @Test
    fun `a rejected import leaves existing data untouched`() = runBlocking {
        importJson(document("1.1", containerJson("BOX-101", itemJson())))

        val bad = importJson(document("1.1", containerJson("BOX-101", itemJson()), containerJson("BOX-101", itemJson())))
        assertTrue(!bad.success)

        assertEquals(1, repository.getAllContainersDirect().size)
    }

    @Test
    fun `export stamps the current version`() = runBlocking {
        val file = File.createTempFile("scantron_export", ".json")
        file.delete()

        manager.exportToJson(Uri.fromFile(file))

        val exported = JSONObject(file.readText())
        assertEquals("1.1", exported.getString("version"))
    }

    @Test
    fun `export emits uuid and a round trip preserves identity`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val saved = repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Drill", barcode = "AAA"))
        assertTrue(saved.uuid.isNotBlank())

        val file = File.createTempFile("scantron_export", ".json")
        file.delete()
        manager.exportToJson(Uri.fromFile(file))

        val itemObj = JSONObject(file.readText())
            .getJSONArray("containers").getJSONObject(0)
            .getJSONArray("items").getJSONObject(0)
        assertEquals(saved.uuid, itemObj.getString("uuid"))

        // Clear the device, then restore from the export: identity must come back intact.
        repository.clearAllData()
        importJson(file.readText())

        assertEquals(saved.uuid, repository.getItemsForContainerDirect("BOX-101").single().uuid)
    }

        // ---- String vs Uri parity ---------------------------------------------------------------
        //
        // The LAN transfer goes through the string half of this manager while USB goes through the
        // Uri half. If the two ever disagree, a file moved over Wi-Fi and the same file moved on a
        // USB stick stop being interchangeable - which is the entire premise of the feature. These
        // tests hold the two paths to identical results for identical input.

        @Test
        fun `the string and Uri export paths produce identical results`() = runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Power Tools"))
            repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Drill", barcode = "AAA"))
            repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Screws", barcode = "BBB"))
            repository.createOrUpdateContainer(Container(id = "EMPTY-1"))

            val file = File.createTempFile("scantron_export", ".json")
            file.delete()
            val viaUri: ExportResult = manager.exportToJson(Uri.fromFile(file))
            val viaString: ExportResult = run {
                // exportToJsonString has no ExportResult of its own, so derive the same one the
                // Uri path would report from the document it produced.
                val json = manager.exportToJsonString()
                val containers = JSONObject(json).getJSONArray("containers")
                var items = 0
                for (i in 0 until containers.length()) {
                    items += containers.getJSONObject(i).optJSONArray("items")?.length() ?: 0
                }
                ExportResult(success = true, containerCount = containers.length(), itemCount = items)
            }

            assertEquals(viaUri, viaString)
            assertEquals(2, viaString.containerCount)
            assertEquals(2, viaString.itemCount)
        }

        @Test
        fun `the string and Uri import paths produce identical results`() = runBlocking {
            val document = document("1.1", containerJson("BOX-101", itemJson(uuid = "abc-123")))

            val viaUri = importJson(document)

            repository.clearAllData()
            val viaString = manager.importFromJsonString(document)

            assertEquals(viaUri, viaString)
            assertTrue(viaString.success)
            assertEquals(1, viaString.containerCount)
            assertEquals(1, viaString.itemCount)
        }

        @Test
        fun `a rejected document is rejected identically through the string path`() = runBlocking {
            // The safety property under test: a document that fails validation must leave the
            // database exactly as it was, whether it arrived on a USB stick or off the LAN.
            importJson(document("1.1", containerJson("BOX-101", itemJson())))

            val bad = document("1.1", containerJson("BOX-101", itemJson()), containerJson("BOX-101", itemJson()))
            val viaUri = importJson(bad)

            repository.clearAllData()
            importJson(document("1.1", containerJson("BOX-101", itemJson())))
            val viaString = manager.importFromJsonString(bad)

            assertEquals(viaUri, viaString)
            assertTrue(!viaString.success)
            assertEquals(
                "a rejected document must not clear the device",
                1,
                repository.getAllContainersDirect().size,
            )
        }

        @Test
        fun `the string export round trips through the string import`() = runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101"))
            val saved = repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Drill", barcode = "AAA"))

            val json = manager.exportToJsonString()
            repository.clearAllData()
            val result = manager.importFromJsonString(json)

            assertTrue(result.success)
            assertEquals(saved.uuid, repository.getItemsForContainerDirect("BOX-101").single().uuid)
        }

        @Test
        fun `a malformed document over the string path changes nothing`() = runBlocking {
            importJson(document("1.1", containerJson("BOX-101", itemJson())))

            val result = manager.importFromJsonString("this is not json at all")

            assertTrue(!result.success)
            assertEquals(1, repository.getAllContainersDirect().size)
        }

        @Test
        fun `an unsupported version over the string path changes nothing`() = runBlocking {
            importJson(document("1.1", containerJson("BOX-101", itemJson())))

            val result = manager.importFromJsonString(document("2.0", containerJson("BOX-9", itemJson())))

            assertTrue(!result.success)
            assertTrue(result.message.contains("Unsupported export version"))
            assertEquals(1, repository.getAllContainersDirect().size)
        }
    }