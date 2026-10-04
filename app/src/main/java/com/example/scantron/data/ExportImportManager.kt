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
  *          "uuid": "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
  *          "updatedAt": 1705318200000
  *        }
  *      ]
  *    }
  *  ]
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
     * @return the number of containers written, or `-1` if the export failed.
     */
    suspend fun exportToJson(outputUri: Uri): ExportResult = try {
        val containers = repository.getAllContainersDirect()
        val payload = containers.map { container ->
            ExportContainer(
                container = container,
                items = repository.getItemsForContainerDirect(container.id),
            )
        }
        val json = buildExportDocument(payload)
        writeJsonToUri(outputUri, json)

        val itemCount = payload.sumOf { it.items.size }
        Log.i(tag, "Exported ${payload.size} containers / $itemCount items to $outputUri")
        ExportResult(success = true, containerCount = payload.size, itemCount = itemCount)
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
     * Import is destructive: the database is cleared only after the document has been fully
     * parsed and validated, so a corrupt file leaves existing data untouched.
     */
    suspend fun importFromJson(inputUri: Uri): ImportResult = try {
        val parsed = parseImportDocument(readJsonFromUri(inputUri))

        repository.clearAllData()
        repository.importAll(parsed.containers, parsed.items)

        Log.i(
            tag,
            "Imported ${parsed.containers.size} containers / ${parsed.items.size} items from $inputUri",
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
        ImportResult(success = false, message = "File is not valid Scantron JSON")
    } catch (e: Exception) {
        Log.e(tag, "Import failed", e)
        ImportResult(
            success = false,
            message = "Could not import file: ${e.message ?: e::class.java.simpleName}",
        )
    }

    private fun buildExportDocument(payload: List<ExportContainer>): String {
        val root = JSONObject()
            .put(KEY_APP, APP_ID)
            .put(KEY_VERSION, SUPPORTED_VERSION)
            .put(
                KEY_EXPORTED_AT,
                SimpleDateFormat(EXPORT_TIMESTAMP_PATTERN, Locale.US).format(Date()),
            )

        val containersArray = JSONArray()
        payload.forEach { entry ->
            val itemsArray = JSONArray()
            entry.items.forEach { item -> itemsArray.put(buildItemJson(item)) }

            containersArray.put(
                buildContainerJson(entry.container)
                    .put(KEY_ITEMS, itemsArray),
            )
        }

        root.put(KEY_CONTAINERS, containersArray)
        return root.toString(INDENT_SPACES)
    }

    private fun buildContainerJson(container: Container): JSONObject = JSONObject()
        .put(KEY_ID, container.id)
        .put(KEY_NAME, container.name)
        .put(KEY_LOCATION, container.location)
        .put(KEY_NOTES, container.notes)
        .put(KEY_UPDATED_AT, container.updatedAt)

    private fun buildItemJson(item: ContainerItem): JSONObject = JSONObject()
        .put(KEY_NAME, item.name)
        .put(KEY_BARCODE, item.barcode)
        .put(KEY_QUANTITY, item.quantity)
        .put(KEY_CATEGORY, item.category)
        .put(KEY_NOTES, item.notes)
                .put(KEY_UUID, item.uuid)
                .put(KEY_UPDATED_AT, item.updatedAt)

    /**
     * Validates and decodes [json] without touching the database.
     */
    private fun parseImportDocument(json: String): ParsedInventory {
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

            val container = Container(
                id = containerId,
                name = containerObj.optString(KEY_NAME),
                location = containerObj.optString(KEY_LOCATION),
                notes = containerObj.optString(KEY_NOTES),
                updatedAt = containerObj.optLong(KEY_UPDATED_AT, System.currentTimeMillis()),
            )
            containers += container

            val itemsArray = containerObj.optJSONArray(KEY_ITEMS) ?: continue
            for (j in 0 until itemsArray.length()) {
                val itemObj = itemsArray.optJSONObject(j)
                    ?: throw ImportException("Item entry $j in \"$containerId\" is not a valid object")
                items += parseItemJson(itemObj, containerId)
            }
        }

        return ParsedInventory(containers, items)
    }

    private fun parseItemJson(obj: JSONObject, containerId: String): ContainerItem {
        val name = obj.optString(KEY_NAME).trim()
        if (name.isEmpty()) {
            throw ImportException("An item in \"$containerId\" is missing a name")
        }
        return ContainerItem(
            // Row ids are device-local; always insert fresh rows on the target device.
            id = 0L,
            containerId = containerId,
            name = name,
            barcode = obj.optString(KEY_BARCODE),
            quantity = obj.optInt(KEY_QUANTITY, 1),
            category = obj.optString(KEY_CATEGORY),
            notes = obj.optString(KEY_NOTES),
                        // A 1.0 file carries no uuid, and a 1.1 file may omit it. Either way the row is
                        // imported without identity and repository.importAll() mints one on insert, so the
                        // parser must not invent a value that could collide with the file's own.
                        uuid = obj.optString(KEY_UUID).trim(),
                        updatedAt = obj.optLong(KEY_UPDATED_AT, System.currentTimeMillis()),
                    )
    }

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

    private data class ParsedInventory(
        val containers: List<Container>,
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
         * importable and their items receive a fresh uuid during the import (see
         * [parseItemJson]).
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
