package com.example.scantron.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to the tombstone log.
 *
 * Deliberately tiny: a tombstone is only ever written, listed and pruned. Nothing reads one to
 * make a decision about the *local* database, because the local database is the authority on what
 * it holds - a tombstone exists solely to tell a peer what is gone.
 */
@Dao
interface DeletionDao {

    /**
     * Records a deletion, replacing any earlier record of the same one.
     *
     * REPLACE rather than IGNORE so a repeat delete re-stamps `deletedAt`. That matters for the
     * prune: a row re-deleted now must not be pruned on a horizon measured from an old stamp.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(deletion: PendingDeletion)

    @Query("SELECT * FROM pending_deletions ORDER BY deletedAt ASC")
    fun observeAll(): Flow<List<PendingDeletion>>

    @Query("SELECT * FROM pending_deletions ORDER BY deletedAt ASC")
    suspend fun getAll(): List<PendingDeletion>

    @Query("SELECT * FROM pending_deletions WHERE kind = :kind AND id = :id LIMIT 1")
    suspend fun find(kind: String, id: String): PendingDeletion?

    /** Drops a tombstone, used once a peer has acknowledged the deletion. */
    @Query("DELETE FROM pending_deletions WHERE kind = :kind AND id = :id")
    suspend fun delete(kind: String, id: String)

    /**
     * Prunes tombstones older than [cutoffMillis].
     *
     * Bounded on purpose: an unbounded log is a slow disk failure, and a peer that has been away
     * longer than the horizon gets a full snapshot anyway - which carries the deletions implicitly,
     * because a snapshot states what the document *is* rather than what changed.
     */
    @Query("DELETE FROM pending_deletions WHERE deletedAt < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM pending_deletions")
    suspend fun deleteAll()
}
