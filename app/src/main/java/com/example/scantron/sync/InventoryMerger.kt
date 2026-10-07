package com.example.scantron.sync

import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.InventoryDocument
import com.example.scantron.data.ItemKey
import com.example.scantron.data.ItemKeyKind
import com.example.scantron.data.itemKeyOf
import java.util.UUID
import kotlin.math.abs

/**
 * One field on which the two devices genuinely disagreed.
 *
 * Field-for-field the same value the desktop reports as `Merge.ItemConflict`, and rendered as
 * strings rather than typed values for the same reason: the conflict is a presentation of "these
 * two texts disagree", and rendering is the UI's job. Both devices show the same three strings for
 * the same conflict, so neither can be accused of inventing a value.
 */
data class ItemConflict(
    val containerId: String,
    /** Empty for a container-level conflict (its name, location or notes). */
    val itemUuid: String,
    val itemName: String,
    /** "Name", "Quantity", "Location", "Presence", ... */
    val field: String,
    val baseValue: String?,
    val localValue: String?,
    val remoteValue: String?,
)

/** What happened to one row. Mirrors the desktop's `ItemMergeOutcome`, case for case. */
enum class ItemMergeOutcome {
    /** Present in the base and identical on both sides. */
    Unchanged,

    /** Only this device changed it; its version was kept. */
    TookLocal,

    /** Only the peer changed it; the peer's version was kept. */
    TookRemote,

    /** Both sides changed the same field to different values. Never auto-resolved. */
    Conflicted,

    /** Only this device has it. */
    AddedLocally,

    /** Only the peer has it. Must survive the merge or scans are lost. */
    AddedRemotely,

    /** In the base but absent from the peer's view. Carried forward, see [mergeContainer]. */
    DeletedRemotely,
}

/** The result of a three-way merge, shaped like the desktop's `MergeResult`. */
data class MergeResult(
    val document: InventoryDocument,
    val conflicts: List<ItemConflict>,
    val outcomes: Map<String, ItemMergeOutcome>,
    val clockSkewDetected: Boolean,
)

/**
 * Three-way merge of two inventories against the last state both devices agreed on.
 *
 * A deliberate, line-for-line port of the desktop's `Merge/InventoryMerger.cs`. It is a port rather
 * than a "roughly equivalent" implementation because the two sides must reach the *same* answer for
 * the same inputs: if the handheld picked a different winner for a quantity than the desktop did,
 * every sync would produce a fresh conflict and the pair would never settle. Where a desktop rule
 * carries a justification, the rule and the justification are reproduced here so the two files can
 * be diffed by eye.
 *
 * Field-level, not row-level: if one side moved `quantity` and the other moved `category`, both
 * wins are correct and neither is a conflict. Warehouse conflicts are overwhelmingly
 * quantity-versus-metadata, which is what makes this the highest-value behaviour in the file.
 *
 * Conflicts are reported, never resolved. A true conflict is one both sides changed to different
 * values; picking either silently loses data, and the operator has better context than this code.
 */
object InventoryMerger {

    /**
     * Timestamps further than this from the local clock are treated as evidence of skew.
     *
     * Each device stamps `updatedAt` with its own wall clock. If the CK65 is an hour out, naive
     * recency reasoning would discard real handheld edits, so the merge still runs field-by-field
     * but flags the document so the UI can warn that recency-based reasoning is unreliable here.
     */
    const val MAX_PLAUSIBLE_SKEW_MS: Long = 10 * 60 * 1000L

    /** The synthetic field name for "this row exists / does not". */
    const val FIELD_PRESENCE: String = "Presence"

    /**
     * Merges [local] and [remote] against [base].
     *
     * @param base last state both sides agreed on; null on a first sync, where every difference is
     *   taken as new rather than guessed at.
     * @param localNow this device's clock, injected so tests are deterministic.
     */
    fun merge(
        base: InventoryDocument?,
        local: InventoryDocument,
        remote: InventoryDocument,
        localNow: Long = System.currentTimeMillis(),
    ): MergeResult {
        val conflicts = mutableListOf<ItemConflict>()
        val outcomes = mutableMapOf<String, ItemMergeOutcome>()

        val localById = local.containers.associateBy { it.id.trim() }
        val remoteById = remote.containers.associateBy { it.id.trim() }
        val baseById = base?.containers?.associateBy { it.id.trim() } ?: emptyMap()

        val mergedContainers = mutableListOf<Container>()
        val mergedRows = mutableListOf<ContainerItem>()

        // Union of ids, local first so this device's ordering is preserved where it has one.
        val allIds = localById.keys + remoteById.keys.filterNot { localById.containsKey(it) }

        for (id in allIds) {
            val localContainer = localById[id]
            val remoteContainer = remoteById[id]

            // A container missing from the peer's view was either deleted there or simply never
            // synced. Treating absence as "no peer opinion" is the safe reading: it keeps a
            // container this device knows about rather than deleting it on a guess.
            val effectiveLocal = localContainer ?: remoteContainer
            val effectiveRemote = remoteContainer ?: localContainer

            if (effectiveLocal == null || effectiveRemote == null) {
                continue
            }

            val merged = mergeContainer(
                containerId = id,
                local = effectiveLocal,
                remote = effectiveRemote,
                base = baseById[id],
                localRows = local.rowsOf(id),
                remoteRows = remote.rowsOf(id),
                baseRows = base?.rowsOf(id) ?: emptyList(),
                conflicts = conflicts,
                outcomes = outcomes,
                localNow = localNow,
            )

            mergedContainers += merged.container
            mergedRows += merged.rows
        }

        return MergeResult(
            document = InventoryDocument(containers = mergedContainers, items = mergedRows),
            conflicts = conflicts,
            outcomes = outcomes,
            clockSkewDetected = detectClockSkew(local, remote, localNow),
        )
    }

    /** One merged container and the rows that belong to it, so [merge] can keep a flat row list. */
    private data class MergedContainer(val container: Container, val rows: List<ContainerItem>)

    private fun InventoryDocument.rowsOf(containerId: String): List<ContainerItem> = itemsOf(containerId)

    private fun mergeContainer(
        containerId: String,
        local: Container,
        remote: Container,
        base: Container?,
        localRows: List<ContainerItem>,
        remoteRows: List<ContainerItem>,
        baseRows: List<ContainerItem>,
        conflicts: MutableList<ItemConflict>,
        outcomes: MutableMap<String, ItemMergeOutcome>,
        localNow: Long,
    ): MergedContainer {
        // Field-level merge of the container's own metadata, same rules as items. Container-level
        // conflicts carry an empty uuid, which is how the desktop distinguishes them too.
        val name = mergeField(containerId, "", "Name", base?.name, local.name, remote.name, conflicts)
        val location = mergeField(
            containerId, "", "Location", base?.location, local.location, remote.location, conflicts,
        )
        val notes = mergeField(containerId, "", "Notes", base?.notes, local.notes, remote.notes, conflicts)

        val localByKey = indexByKey(containerId, localRows)
        val remoteByKey = indexByKey(containerId, remoteRows)
        val baseByKey = indexByKey(containerId, baseRows)

        val mergedRows = mutableListOf<ContainerItem>()
        val seenKeys = mutableSetOf<ItemKey>()

        for (key in localByKey.keys + remoteByKey.keys.filterNot { localByKey.containsKey(it) }) {
            if (!seenKeys.add(key)) {
                continue
            }

            val localRow = localByKey[key]
            val remoteRow = remoteByKey[key]

            // Same "absence is not a verdict" rule as containers: never drop a row just because one
            // side's view did not list it.
            val effectiveLocal = localRow ?: remoteRow
            val effectiveRemote = remoteRow ?: localRow

            if (effectiveLocal == null || effectiveRemote == null) {
                continue
            }

            mergedRows += mergeItem(                containerId = containerId,
                local = effectiveLocal,
                remote = effectiveRemote,
                base = baseByKey[key],
                presentLocally = localRow != null,
                presentRemotely = remoteRow != null,
                conflicts = conflicts,
                outcomes = outcomes,
                localNow = localNow,
            )
        }

        // A row that existed at the last sync and is now gone from the peer's view is flagged rather
        // than resurrected blindly: it may be a deliberate delete. It is carried into the output so
        // the operator can decide, which is safer than dropping stock silently.
        for ((key, baseRow) in baseByKey) {
            if (localByKey.containsKey(key) || remoteByKey.containsKey(key)) {
                continue
            }

            outcomes[outcomeKey(containerId, baseRow)] = ItemMergeOutcome.DeletedRemotely
            conflicts += ItemConflict(
                containerId = containerId,
                itemUuid = baseRow.uuid,
                itemName = baseRow.name,
                field = FIELD_PRESENCE,
                baseValue = "present",
                localValue = "absent",
                remoteValue = "absent",
            )
        }

        return MergedContainer(
            container = Container(
                id = containerId,
                name = name,
                location = location,
                notes = notes,
                // Never older than either input, so a merge cannot lose again on the next sync.
                updatedAt = maxOf(
                    local.updatedAt,
                    remote.updatedAt,
                    mergedRows.maxOfOrNull { it.updatedAt } ?: 0L,
                ),
            ),
            rows = mergedRows,
        )
    }

    private fun mergeItem(
        containerId: String,
        local: ContainerItem,
        remote: ContainerItem,
        base: ContainerItem?,
        presentLocally: Boolean,
        presentRemotely: Boolean,
        conflicts: MutableList<ItemConflict>,
        outcomes: MutableMap<String, ItemMergeOutcome>,
        localNow: Long,
    ): ContainerItem {
        val outcome = classifyOutcome(base, local, remote, presentLocally, presentRemotely)

        if (outcome == ItemMergeOutcome.AddedLocally || outcome == ItemMergeOutcome.AddedRemotely) {
            outcomes[outcomeKey(containerId, local)] = outcome

            // An added row is taken wholesale: there is no base to diff against, so any attempt at
            // field merging would be invention. The timestamp is still advanced so the row sorts
            // sensibly and wins the next sync.
            val adopted = if (outcome == ItemMergeOutcome.AddedLocally) local else remote
            return stamp(adopted, maxOf(local.updatedAt, remote.updatedAt), localNow)
        }

        val merged = local.copy(
            // Identity is taken from whichever side has one. A legacy base can leave both sides
            // without a uuid; the merged row is then minted so the output is a valid 1.1 document
            // rather than silently re-introducing unidentifiable rows.
            uuid = firstNonBlank(local.uuid, remote.uuid) ?: UUID.randomUUID().toString(),
            name = mergeField(containerId, local.uuid, "Name", base?.name, local.name, remote.name, conflicts),
            barcode = mergeField(containerId, local.uuid, "Barcode", base?.barcode, local.barcode, remote.barcode, conflicts),
            quantity = mergeField(containerId, local.uuid, "Quantity", base?.quantity, local.quantity, remote.quantity, conflicts),
            category = mergeField(containerId, local.uuid, "Category", base?.category, local.category, remote.category, conflicts),
            notes = mergeField(containerId, local.uuid, "Notes", base?.notes, local.notes, remote.notes, conflicts),
        )

        outcomes[outcomeKey(containerId, local)] = outcome
        return stamp(merged, maxOf(local.updatedAt, remote.updatedAt), localNow)
    }

    private fun classifyOutcome(
        base: ContainerItem?,
        local: ContainerItem,
        remote: ContainerItem,
        presentLocally: Boolean,
        presentRemotely: Boolean,
    ): ItemMergeOutcome {
        if (presentLocally && !presentRemotely) return ItemMergeOutcome.AddedLocally
        if (!presentLocally && presentRemotely) return ItemMergeOutcome.AddedRemotely

        // No base row means one side has no opinion to compare against, so this is a first sync
        // rather than a conflict: everything is either unchanged or a local-new win.
        if (base == null) {
            return if (sameContent(local, remote)) ItemMergeOutcome.Unchanged else ItemMergeOutcome.TookLocal
        }

        if (sameContent(local, remote)) return ItemMergeOutcome.Unchanged
        return if (sameContent(local, base)) ItemMergeOutcome.TookRemote else ItemMergeOutcome.TookLocal
    }

    /**
     * Three-way resolution for one value.
     *
     * The `Int` overload below is the same rule spelled for quantities; both add to [conflicts] and
     * both return the local value on a true disagreement, because silently discarding a quantity is
     * the exact failure this design exists to prevent.
     */
    internal fun mergeField(
        containerId: String,
        itemUuid: String,
        field: String,
        baseValue: String?,
        local: String,
        remote: String,
        conflicts: MutableList<ItemConflict>,
    ): String {
        if (local == remote) return local

        // No base means no shared history, so there is nothing to have "disagreed" about. Reporting
        // a conflict here would show the operator a field they have no way to resolve, on a first
        // sync where every row looks like one. Local wins by default because this is the side the
        // operator is looking at.
        if (baseValue == null) return local

        if (local == baseValue) return remote
        if (remote == baseValue) return local

        conflicts += ItemConflict(
            containerId = containerId,
            itemUuid = itemUuid,
            itemName = "",
            field = field,
            baseValue = baseValue,
            localValue = local,
            remoteValue = remote,
        )
        return local
    }

    private fun mergeField(
        containerId: String,
        itemUuid: String,
        field: String,
        baseValue: Int?,
        local: Int,
        remote: Int,
        conflicts: MutableList<ItemConflict>,
    ): Int {
        if (local == remote) return local
        if (baseValue == null) return local
        if (local == baseValue) return remote
        if (remote == baseValue) return local

        conflicts += ItemConflict(
            containerId = containerId,
            itemUuid = itemUuid,
            itemName = "",
            field = field,
            baseValue = baseValue.toString(),
            localValue = local.toString(),
            remoteValue = remote.toString(),
        )
        return local
    }

    /**
     * Advances a merged row's timestamp past every input and past now.
     *
     * A merge that emitted the older `updatedAt` would lose the same argument again on the next
     * sync, so the result is always at least "now".
     */
    private fun stamp(item: ContainerItem, newestInput: Long, localNow: Long): ContainerItem =
        item.copy(updatedAt = maxOf(item.updatedAt, maxOf(newestInput, localNow)))

    /**
     * True when two rows are the same content.
     *
     * Compares fields rather than using `data class` equality, because the stored row id is
     * device-local: two devices hold the *same* row under different ids, and comparing ids would
     * report every row as changed on every sync.
     */
    private fun sameContent(left: ContainerItem, right: ContainerItem): Boolean =
        left.uuid == right.uuid &&
            left.containerId == right.containerId &&
            left.name == right.name &&
            left.barcode == right.barcode &&
            left.quantity == right.quantity &&
            left.category == right.category &&
            left.notes == right.notes

    /**
     * Indexes rows by merge identity, re-minting a duplicate uuid rather than collapsing two rows.
     *
     * Mirrors the desktop's `ItemKeyResolver.IndexByKey`. The two key kinds resolve collisions
     * differently because the devices do: a uuid collision means one identity is claimed by two
     * rows, which the repository's `assignMissingItemUuids` resolves by keeping the first and
     * re-minting the rest, so both rows survive as separate items. Collapsing them would drop stock.
     * The heuristic kinds can only collide in legacy documents, where the keys are genuinely
     * ambiguous and the newest `updatedAt` wins - matching the device's `ORDER BY updatedAt DESC`
     * before `LIMIT 1`.
     */
    internal fun indexByKey(containerId: String, rows: List<ContainerItem>): Map<ItemKey, ContainerItem> {
        val index = LinkedHashMap<ItemKey, ContainerItem>()

        for (row in rows) {
            val key = itemKeyOf(containerId, row)
            val existing = index[key]

            if (existing == null) {
                index[key] = row
                continue
            }

            if (key.kind == ItemKeyKind.Uuid) {
                var candidate = row.copy(uuid = UUID.randomUUID().toString())
                var candidateKey = itemKeyOf(containerId, candidate)
                while (index.containsKey(candidateKey)) {
                    candidate = row.copy(uuid = UUID.randomUUID().toString())
                    candidateKey = itemKeyOf(containerId, candidate)
                }
                index[candidateKey] = candidate
                continue
            }

            if (row.updatedAt > existing.updatedAt) {
                index[key] = row
            }
        }

        return index
    }

    /**
     * True when any timestamp in either input is implausibly far from [localNow].
     *
     * Container timestamps count as well as row timestamps, matching the desktop: a container whose
     * metadata was edited on a skewed clock is exactly as suspicious as a row.
     */
    private fun detectClockSkew(
        local: InventoryDocument,
        remote: InventoryDocument,
        localNow: Long,
    ): Boolean = (local.allTimestamps() + remote.allTimestamps())
        .any { abs(it - localNow) > MAX_PLAUSIBLE_SKEW_MS }

    private fun InventoryDocument.allTimestamps(): List<Long> =
        containers.map { it.updatedAt } + items.map { it.updatedAt }

    private fun outcomeKey(containerId: String, item: ContainerItem): String =
        "${containerId.trim()}/${item.uuid.trim()}/${itemKeyOf(containerId, item).value}"

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { it.isNotBlank() }?.trim()
}
