package com.example.scantron.sync

import com.example.scantron.data.InventoryDocument
import com.example.scantron.data.ItemKey
import com.example.scantron.data.itemKeyOf

/**
 * The outcome of applying a peer's ops to the shared base.
 *
 * @param document what this device should now show, converged where it can be.
 * @param base the new shared base. Advanced for every field the two devices agree on, and
 *   deliberately *not* advanced for a conflicted field. This is the value that must be persisted as
 *   the base after the batch is durably applied - a base advanced too far is what lets a real
 *   conflict be silently overwritten on the next round.
 * @param conflicts same-field disagreements needing a human, whose base entry did not move.
 * @param clockSkewDetected passed through from [InventoryMerger].
 */
data class SyncApplyResult(
    val document: InventoryDocument,
    val base: InventoryDocument,
    val conflicts: List<ItemConflict>,
    val clockSkewDetected: Boolean,
)

/**
 * Applies a peer's ops to the shared base, then converges the two devices through the one merge
 * implementation this project has.
 *
 * A port of the desktop's `Sync/SyncApply.cs`. The receiver does not try to be clever about the
 * wire. It first reconstructs what the peer now holds - *base + ops* - and then asks
 * [InventoryMerger] the same three-way question it would ask for a USB import: given what we agreed
 * on, what did each side change? Keeping that comparison in one place is what makes a live sync and
 * a file transfer behave identically, down to the conflict rules.
 *
 * The only live-sync-specific rule is how the base advances: for a field both sides changed to
 * different values, the base *stays put*. Advancing it would declare one side the winner, which is
 * exactly the silent data loss the whole design exists to prevent, and it would also make the
 * conflict unreproducible on the next round. Keeping the base at the last agreed value is what makes
 * a conflict durable until a human settles it.
 *
 * A replay is harmless. Because the base is only advanced for what both sides agreed on, applying the
 * identical batch twice produces the same result the second time and reports no new change - which is
 * what makes reconnect-and-resend safe without any de-duplication bookkeeping.
 */
object SyncApply {

    fun apply(
        base: InventoryDocument?,
        local: InventoryDocument,
        ops: List<SyncOp>,
        now: Long = System.currentTimeMillis(),
    ): SyncApplyResult {
        val remote = reconstruct(base, ops)
        val merged = InventoryMerger.merge(base, local, remote, now)

        // The displayed document is exactly what the merge produced: each side keeps seeing its own
        // value for a field it is right about, which avoids an operator's edit appearing to vanish
        // the moment a peer's change lands. Convergence is achieved through the base below.
        val withDeletesHonoured = honourExplicitDeletes(merged.document, ops)
        val nextBase = revertConflictedFields(withDeletesHonoured, base, merged.conflicts)

        return SyncApplyResult(
            document = withDeletesHonoured,
            base = nextBase,
            conflicts = merged.conflicts,
            clockSkewDetected = merged.clockSkewDetected,
        )
    }

    /**
     * Removes what the batch explicitly deleted, including from the displayed document.
     *
     * This is the one place an *explicit* deletion is distinguished from a mere absence, and the
     * distinction is not cosmetic. [InventoryMerger] treats a row missing from one side as "no peer
     * opinion" and carries it forward - deliberately, because absence alone cannot tell a delete from a
     * container that has simply never been synced. A `DeleteItemOp` is not an absence: it is the peer
     * stating that the row is gone, and it is the only thing on this wire that can say so.
     *
     * Honouring it only in the base - which is what the merge alone would do - would leave the row in
     * the document, and the very next diff would see a row present locally and absent from the base and
     * re-emit it as an upsert. The deletion would then never take effect and would echo forever, which
     * is the exact failure the whole base-advancing design exists to prevent.
     *
     * An explicit delete wins over a simultaneous local edit. That is the standard reading of a
     * tombstone, and the alternative - a conflict on a row that no longer exists - has no value an
     * operator could choose between.
     */
    private fun honourExplicitDeletes(document: InventoryDocument, ops: List<SyncOp>): InventoryDocument {
        var result = document

        for (op in ops) {
            when (op) {
                is DeleteContainerOp -> result = SyncDocuments.removeContainer(result, op.containerId)

                is DeleteItemOp -> result = SyncDocuments.removeItem(
                    result,
                    op.containerId,
                    ItemKey(op.keyKind, op.keyValue),
                )

                else -> Unit
            }
        }

        return result
    }

    /**
     * Rebuilds the peer's document from the shared base plus its ops.
     *
     * Exposed because this is the one step where a bug is invisible: a wrong reconstruction looks
     * like a legitimate merge outcome. Tests pin it directly rather than inferring it from a merge.
     */
    fun reconstruct(base: InventoryDocument?, ops: List<SyncOp>): InventoryDocument {
        var document = base ?: SyncDocuments.EMPTY

        for (op in ops) {
            document = when (op) {
                is SnapshotOp -> op.document

                // A container upsert deliberately carries no rows, so only its metadata replaces
                // anything. Applying it must not drop the rows the base already holds.
                is UpsertContainerOp -> SyncDocuments.upsertContainer(
                    document,
                    op.container,
                    document.itemsOf(op.container.id),
                )

                is UpsertItemOp -> SyncDocuments.upsertItem(document, op.containerId, op.item)

                is DeleteContainerOp -> SyncDocuments.removeContainer(document, op.containerId)

                is DeleteItemOp -> SyncDocuments.removeItem(
                    document,
                    op.containerId,
                    ItemKey(op.keyKind, op.keyValue),
                )

                is ResolveOp -> applyResolution(document, op)
            }
        }

        return document
    }

    /**
     * Applies an operator's decision. A decision is not a simultaneous edit, so it simply sets the
     * field - there is nothing to conflict with.
     *
     * The row is found by uuid rather than by the merge key, because the conflict the operator
     * answered was already resolved to a specific row on both devices when it was reported.
     */
    private fun applyResolution(document: InventoryDocument, op: ResolveOp): InventoryDocument {
        if (op.itemUuid.isBlank()) {
            return SyncDocuments.setContainerField(document, op.containerId, op.field, op.value)
        }

        val row = document.itemsOf(op.containerId)
            .firstOrNull { it.uuid.trim() == op.itemUuid.trim() }
            ?: return document

        return SyncDocuments.setItemField(
            document,
            op.containerId,
            itemKeyOf(op.containerId, row),
            op.field,
            op.value,
        )
    }

    /**
     * Walks a document back to the last agreed value for every conflicted field, giving the new
     * base. Non-conflicted fields keep the merged value, so they advance normally.
     */
    private fun revertConflictedFields(
        document: InventoryDocument,
        base: InventoryDocument?,
        conflicts: List<ItemConflict>,
    ): InventoryDocument {
        if (conflicts.isEmpty()) {
            return document
        }

        var reverted = document

        for (conflict in conflicts) {
            // "Presence" is not a field, it is the row's existence. The merger already carries the
            // row forward rather than dropping it, so the last agreed answer is "present" and there
            // is nothing to walk back.
            if (conflict.field == InventoryMerger.FIELD_PRESENCE) {
                continue
            }

            reverted = revertField(reverted, conflict, baseValueOf(base, conflict))
        }

        return reverted
    }

    private fun revertField(
        document: InventoryDocument,
        conflict: ItemConflict,
        agreed: String?,
    ): InventoryDocument = if (conflict.itemUuid.isBlank()) {
        // A container-level conflict carries an empty uuid, which is exactly how the conflict was
        // reported, so the same value routes it back to the container.
        SyncDocuments.setContainerField(document, conflict.containerId, conflict.field, agreed)
    } else {
        revertRow(document, conflict, agreed)
    }

    private fun revertRow(
        document: InventoryDocument,
        conflict: ItemConflict,
        agreed: String?,
    ): InventoryDocument {
        // A row conflict is keyed by uuid on both sides, so the identity is unambiguous here.
        val row = document.itemsOf(conflict.containerId)
            .firstOrNull { it.uuid.trim() == conflict.itemUuid.trim() }
            ?: return document

        return SyncDocuments.setItemField(
            document,
            conflict.containerId,
            itemKeyOf(conflict.containerId, row),
            conflict.field,
            agreed,
        )
    }

    /**
     * The last agreed value of a conflicted field, or null when the base never held the row.
     *
     * A null here means the field is absent from the base, and writing that back is correct: it
     * restores "we never agreed on this", which is precisely why it conflicted.
     */
    private fun baseValueOf(base: InventoryDocument?, conflict: ItemConflict): String? {
        if (base == null) {
            return null
        }

        if (conflict.itemUuid.isBlank()) {
            val container = base.container(conflict.containerId) ?: return null
            return when (conflict.field) {
                "Name" -> container.name
                "Location" -> container.location
                "Notes" -> container.notes
                else -> null
            }
        }

        val row = base.itemsOf(conflict.containerId)
            .firstOrNull { it.uuid.trim() == conflict.itemUuid.trim() }
            ?: return null

        return when (conflict.field) {
            "Name" -> row.name
            "Barcode" -> row.barcode
            "Quantity" -> row.quantity.toString()
            "Category" -> row.category
            "Notes" -> row.notes
            else -> null
        }
    }
}
