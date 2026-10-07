package com.example.scantron.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serializes the full container inventory to a portable JSON document and restores it on another
 * device.
 *
 * Document shape:
 * ```json
 * {
 *   "app": "Scantron",
 *   "version": "1.0",
 *   "exportedAt": "2024-01-15T10:30:00Z",
 *   "containers": [
 *     {
 *       "id": "BOX-101",
 *       "name": "Power Tools & Hardware",
 *       "location": "Garage Shelf 2-A",
 *       "notes": "Heavy plastic storage bin",
 *       "updatedAt": 1705318200000,
 *       "items": [
 *         {
 *           "name": "Cordless Drill 18V",
 *           "barcode": "088381699921",
 *           "quantity": 1,
 *           "category": "Tools",
 *           "notes": "Includes 2 lithium batteries",
 *           "uuid": "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
 *           "updatedAt": 1705318200000
 *         }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * Version history:
 *  - `1.0` - original shape, items have no identity field.
 *  - `1.1` - adds the per-item `uuid`, a stable identity that survives export/import so a
 *    desktop edit and a handheld edit can be matched to the same item. Both versions import.
 */
class ExportImportManager(
    private val context: Context,
    private val repository: ContainerRepository,
) {

    private val tag = "ExportImportManager"

    /**
     * Writes every container and its items to [outputUri].
     *
     * Thin wrapper over [exportToJsonString] + a file write. The document is built by exactly
     * the same code either way, so a file exported to a USB stick and the same bytes handed to
     * the LAN transfer hub are the same document.
     */
    suspend fun exportToJson(outputUri: Uri): ExportResult = try {
        val payload = collectExportPayload()
        writeJsonToUri(outputUri, buildExportDocument(payload))

        val (containerCount, itemCount) = countOf(payload)
        Log.i(tag, "Exported $containerCount containers / $itemCount items to $outputUri")
        ExportResult(success = true, containerCount = containerCount, itemCount = itemCount)
    } catch (e: ImportException) {
        Log.w(tag, "Export rejected: ${e.message}", e)
        ExportResult(success = false, message = e.message ?: "Could not export inventory")
    } catch (e: Exception) {
        Log.e(tag, "Export failed", e)
        ExportResult(
            success = false,
            message = "Could not export file: ${e.message ?: e::class.java.simpleName}",
        )
    }

    /**
     * Replaces the current database contents with the contents of [inputUri].
     *
     * Thin wrapper over [importFromJsonString] + a file read. A file import and a document pulled
     * off the LAN are the same code path from the first byte onwards.
     */
    suspend fun importFromJson(inputUri: Uri): ImportResult =
        importFromJsonString(readJsonFromUri(inputUri), source = inputUri.toString())

    /**
     * Serializes the whole inventory to the document string, without an I/O target.
     *
     * This is what the LAN transfer pushes. The bytes are the unmodified Scantron export
     * document - the same thing a USB file would contain - because both sides already have a
     * parser and validator for it. Wrapping it in an envelope would mean two formats to keep in
     * step and two new ways for the desktop and the handheld to disagree.
     *
     * @throws ImportException if the inventory cannot be assembled into a document.
     */
    suspend fun exportToJsonString(): String {
        val payload = collectExportPayload()
        Log.i(
            tag,
            "Built export document: ${payload.size} containers / ${payload.sumOf { it.items.size }} items",
        )
        return buildExportDocument(payload)
    }

    /**
     * Replaces the current database contents with the contents of [json].
     *
     * This is what the LAN transfer feeds a `/pull` response into. Import is destructive: the
     * database is cleared only after the document has been fully parsed and validated, so a
     * corrupt, truncated, or hostile document leaves existing data untouched. That ordering is
     * the whole safety story for pull-import and must not be relaxed - the desktop serving an
     * empty or broken document would otherwise wipe a day of warehouse scanning.
     */
    suspend fun importFromJsonString(json: String, source: String = "transfer"): ImportResult = try {
        val parsed = parseDocument(json)

        repository.clearAllData()
        repository.importAll(parsed.containers, parsed.items)

        Log.i(
            tag,
            "Imported ${parsed.containers.size} containers / ${parsed.items.size} items from $source",
        )
        ImportResult(
            success = true,
            message = buildString {
                append("Imported ${parsed.containers.size} containers")
                if (parsed.items.isNotEmpty()) {
                    append(" and ${parsed.items.size} items")
                }
            },
            containerCount = parsed.containers.size,
            itemCount = parsed.items.size,
        )
    } catch (e: ImportException) {
        Log.w(tag, "Import rejected: ${e.message}", e)
        ImportResult(success = false, message = e.message ?: "Could not import inventory")
    } catch (e: JSONException) {
        Log.w(tag, "Import rejected: malformed JSON", e)
        ImportResult(success = false, message = "Document is not valid Scantron JSON")
    } catch (e: Exception) {
        Log.e(tag, "Import failed", e)
        ImportResult(
            success = false,
            message = "Could not import inventory: ${e.message ?: e::class.java.simpleName}",
        )
    }

    /**
     * Snapshot of every container plus its items, read straight from the database.
     *
     * Read once per export so the document is internally consistent: pulling each container's
     * items separately would interleave with concurrent scans and produce a document whose item
     * counts disagree with the containers it was taken from.
     */
    private suspend fun collectExportPayload(): List<ExportContainer> =
        repository.getAllContainersDirect().map { container ->
            ExportContainer(
                container = container,
                items = repository.getItemsForContainerDirect(container.id),
            )
        }

    private fun countOf(payload: List<ExportContainer>): Pair<Int, Int> =
        payload.size to payload.sumOf { it.items.size }

    private fun buildExportDocument(payload: List<ExportContainer>): String = buildDocument(
        containers = payload.map { it.container },
        items = payload.flatMap { it.items },
    )

    private fun writeJsonToUri(uri: Uri, json: String) {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            BufferedWriter(OutputStreamWriter(outputStream)).use { it.write(json) }
        } ?: throw ImportException("Could not open $uri for writing")
    }

    private fun readJsonFromUri(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader -> reader.readText() }
        } ?: throw ImportException("Could not open $uri for reading")

    private data class ExportContainer(
        val container: Container,
        val items: List<ContainerItem>,
    )

    companion object {
        const val DEFAULT_FILENAME = "scantron_inventory.json"
        const val MIME_TYPE = "application/json"

        /**
         * Version stamped onto new exports. 1.1 adds the per-item `uuid` identity field.
         */
        const val SUPPORTED_VERSION = "1.1"

        /**
         * Every version this build can import. 1.0 files predate item identity; they remain
         * importable and their items receive a fresh uuid on the way in (see [parseDocument]).
         */
        private val IMPORTABLE_VERSIONS = setOf("1.0", SUPPORTED_VERSION)
        private const val APP_ID = "Scantron"
        private const val EXPORT_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        private const val INDENT_SPACES = 2

        private const val KEY_APP = "app"
        private const val KEY_VERSION = "version"
        private const val KEY_EXPORTED_AT = "exportedAt"
        private const val KEY_CONTAINERS = "containers"
        private const val KEY_ITEMS = "items"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_LOCATION = "location"
        private const val KEY_NOTES = "notes"
        private const val KEY_BARCODE = "barcode"
        private const val KEY_QUANTITY = "quantity"
        private const val KEY_CATEGORY = "category"
        private const val KEY_UUID = "uuid"
        private const val KEY_UPDATED_AT = "updatedAt"

        /**
         * Reads and validates a document without touching the database.
         *
         * The one parser for the export format, reachable from the file-import path and from live
         * sync. Answering both from here is deliberate: the export document *is* the contract, and a
         * second reader would be a second place for the two devices to disagree about what is valid
         * - letting a peer place data in the database by a route a USB import would have refused.
         *
         * Companion rather than instance because the sync layer holds a store and a repository, not
         * a manager, and pulling a `Context` into it just to reach this code would make the sync
         * engine untestable without one.
         *
         * @throws ImportException when the text is readable JSON but is not an importable document.
         * @throws JSONException when the text is not JSON at all.
         */
        @Throws(ImportException::class, JSONException::class)
        fun parseDocument(json: String): InventoryDocument {
            val root = JSONObject(json)

            val version = root.optString(KEY_VERSION, SUPPORTED_VERSION)
            if (version !in IMPORTABLE_VERSIONS) {
                throw ImportException(
                    "Unsupported export version \"$version\" (expected one of ${IMPORTABLE_VERSIONS.joinToString()})",
                )
            }

            val containersArray = root.optJSONArray(KEY_CONTAINERS)
                ?: throw ImportException("File is missing a \"$KEY_CONTAINERS\" array")

            val containers = mutableListOf<Container>()
            val items = mutableListOf<ContainerItem>()
            val seenIds = mutableSetOf<String>()

            for (i in 0 until containersArray.length()) {
                val containerObj = containersArray.optJSONObject(i)
                    ?: throw ImportException("Container entry $i is not a valid object")

                val containerId = containerObj.optString(KEY_ID).trim()
                if (containerId.isEmpty()) {
                    throw ImportException("Container entry $i is missing a tag id")
                }
                if (!seenIds.add(containerId)) {
                    throw ImportException("Duplicate container tag \"$containerId\" in file")
                }

                containers += Container(
                    id = containerId,
                    name = containerObj.optString(KEY_NAME),
                    location = containerObj.optString(KEY_LOCATION),
                    notes = containerObj.optString(KEY_NOTES),
                    updatedAt = containerObj.optLong(KEY_UPDATED_AT, System.currentTimeMillis()),
                )

                val itemsArray = containerObj.optJSONArray(KEY_ITEMS) ?: continue
                for (j in 0 until itemsArray.length()) {
                    val itemObj = itemsArray.optJSONObject(j)
                        ?: throw ImportException("Item entry $j in \"$containerId\" is not a valid object")

                    val name = itemObj.optString(KEY_NAME).trim()
                    if (name.isEmpty()) {
                        throw ImportException("An item in \"$containerId\" is missing a name")
                    }

                    items += ContainerItem(
                        // Row ids are device-local; always insert fresh rows on the target device.
                        id = 0L,
                        containerId = containerId,
                        name = name,
                        barcode = itemObj.optString(KEY_BARCODE),
                        quantity = itemObj.optInt(KEY_QUANTITY, 1),
                        category = itemObj.optString(KEY_CATEGORY),
                        notes = itemObj.optString(KEY_NOTES),
                        // A 1.0 file carries no uuid, and a 1.1 file may omit it. Either way the row
                        // is imported without identity and repository.importAll() mints one on
                        // insert, so the parser must not invent a value that could collide with the
                        // file's own.
                        uuid = itemObj.optString(KEY_UUID).trim(),
                        updatedAt = itemObj.optLong(KEY_UPDATED_AT, System.currentTimeMillis()),
                    )
                }
            }

            return InventoryDocument(containers = containers, items = items)
        }

        /**
         * Builds a document from containers and rows.
         *
         * A sync-applied batch writes its own document rather than going through
         * [exportToJsonString], because it already holds the merged rows in memory and re-reading the
         * database would both cost a round trip and risk picking up a scan that landed mid-merge.
         * Routing both writers through here is what keeps the two byte-identical in meaning, which
         * the round-trip test asserts.
         */
        fun buildDocument(
            containers: List<Container>,
            items: List<ContainerItem>,
            exportedAtMillis: Long = System.currentTimeMillis(),
        ): String {
            val root = JSONObject()
                .put(KEY_APP, APP_ID)
                .put(KEY_VERSION, SUPPORTED_VERSION)
                .put(
                    KEY_EXPORTED_AT,
                    SimpleDateFormat(EXPORT_TIMESTAMP_PATTERN, Locale.US).format(Date(exportedAtMillis)),
                )

            val containersArray = JSONArray()
            containers.forEach { container ->
                val itemsArray = JSONArray()
                items.filter { it.containerId.trim() == container.id.trim() }.forEach { item ->
                    itemsArray.put(buildItemJson(item))
                }
                containersArray.put(buildContainerJson(container).put(KEY_ITEMS, itemsArray))
            }

            root.put(KEY_CONTAINERS, containersArray)
            return root.toString(INDENT_SPACES)
        }

        /** Serializes one container entry, in the shape [buildDocument] expects. */
        fun buildContainerJson(container: Container): JSONObject = JSONObject()
            .put(KEY_ID, container.id)
            .put(KEY_NAME, container.name)
            .put(KEY_LOCATION, container.location)
            .put(KEY_NOTES, container.notes)
            .put(KEY_UPDATED_AT, container.updatedAt)

        /** Serializes one item entry, in the shape [buildDocument] expects. */
        fun buildItemJson(item: ContainerItem): JSONObject = JSONObject()
            .put(KEY_NAME, item.name)
            .put(KEY_BARCODE, item.barcode)
            .put(KEY_QUANTITY, item.quantity)
            .put(KEY_CATEGORY, item.category)
            .put(KEY_NOTES, item.notes)
            .put(KEY_UUID, item.uuid)
            .put(KEY_UPDATED_AT, item.updatedAt)
    }
}

/** Outcome of an export attempt, suitable for direct display in the UI. */
data class ExportResult(
    val success: Boolean,
    val message: String = "",
    val containerCount: Int = 0,
    val itemCount: Int = 0,
)

/** Outcome of an import attempt, suitable for direct display in the UI. */
data class ImportResult(
    val success: Boolean,
    val message: String,
    val containerCount: Int = 0,
    val itemCount: Int = 0,
)

/** Raised when a file is readable but is not a usable Scantron export. */
class ImportException(message: String) : Exception(message)
