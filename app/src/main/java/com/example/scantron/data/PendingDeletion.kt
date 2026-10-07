package com.example.scantron.data

import androidx.room.Entity
import androidx.room.Index

/**
 * A row or container this device deleted, remembered so the deletion can still be told to a peer.
 *
 * The base document already expresses a deletion as "present in base, absent now", and for a peer
 * that is still paired that is enough. The tombstone covers the case the base cannot: a peer that
 * was away long enough that the row has fallen out of both the base and the 30-day horizon, or a
 * base that had to be discarded. Without it the peer would keep resurrecting the row, because
 * "absent" on the wire means "no opinion" and not "gone".
 *
 * [id] is the uuid for an item row and the container tag for a container, which is exactly the
 * identity a [DeleteItemOp][SyncOp] carries. The primary key is (kind, id) rather than a synthetic
 * row id so recording the same deletion twice is an idempotent replace instead of a duplicate.
 */
@Entity(
    tableName = "pending_deletions",
    primaryKeys = ["kind", "id"],
    // Indexed because the only non-trivial query on this table is the age-based prune, and it
    // runs on every sync handshake. Declared here as well as in the migration so Room's schema
    // validation at open time sees exactly what the migration created.
    indices = [Index("deletedAt")],
)
data class PendingDeletion(
    /** "item" or "container"; see [PendingDeletionKind]. */
    val kind: String,
    /** Item uuid, or container tag. */
    val id: String,
    /** Owning container tag; equals [id] for a container tombstone. */
    val containerId: String,
    /** When the deletion happened, on this device's clock. */
    val deletedAt: Long,
)

/** The two things that can be deleted, as stored in [PendingDeletion.kind]. */
object PendingDeletionKind {
    const val ITEM = "item"
    const val CONTAINER = "container"
}
