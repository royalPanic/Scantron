package com.example.scantron.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM container_items WHERE containerId = :containerId ORDER BY updatedAt DESC")
    fun getItemsForContainer(containerId: String): Flow<List<ContainerItem>>

    @Query(
        "SELECT * FROM container_items WHERE containerId = :containerId " +
            "AND TRIM(barcode) = TRIM(:barcode) LIMIT 1",
    )
    suspend fun getItemByBarcode(containerId: String, barcode: String): ContainerItem?

    /**
     * Finds a name-only item (one with no barcode of its own) whose name matches [name],
     * ignoring case and surrounding whitespace.
     *
     * Restricted to blank-barcode rows on purpose: when an item already carries a barcode that
     * barcode is its identity, and matching it by name could silently merge two different
     * things. Rows without a barcode have no other way to be recognised.
     */
    @Query(
        "SELECT * FROM container_items WHERE containerId = :containerId " +
            "AND TRIM(barcode) = '' " +
            "AND LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1",
    )
    suspend fun getNameOnlyItemByName(containerId: String, name: String): ContainerItem?

    @Query("SELECT COUNT(*) FROM container_items WHERE containerId = :containerId")
    fun getItemCountForContainer(containerId: String): Flow<Int>

    /**
     * Total number of units in a container, summing each item's quantity rather than counting
     * rows. `COALESCE` is required because `SUM` over no rows yields NULL, which does not map
     * onto the non-null [Int] return type.
     */
    @Query(
        "SELECT COALESCE(SUM(quantity), 0) FROM container_items " +
            "WHERE containerId = :containerId",
    )
    fun getTotalQuantityForContainer(containerId: String): Flow<Int>

    /** Reads a row's stored uuid, so an update can preserve an identity it was not given. */
        @Query("SELECT uuid FROM container_items WHERE id = :itemId LIMIT 1")
        suspend fun getUuidById(itemId: Long): String?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        suspend fun insertItem(item: ContainerItem)

    @Update
    suspend fun updateItem(item: ContainerItem)

    @Delete
    suspend fun deleteItem(item: ContainerItem)

    @Query("DELETE FROM container_items")
    suspend fun deleteAllItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllItems(items: List<ContainerItem>)

    @Transaction
    @Query(
        "SELECT * FROM container_items " +
            "WHERE name LIKE '%' || :query || '%' " +
            "OR barcode LIKE '%' || :query || '%' " +
            "OR category LIKE '%' || :query || '%' " +
            "OR notes LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC",
    )
    fun searchItemsAcrossContainers(query: String): Flow<List<ItemWithContainer>>

    @Transaction
    @Query("SELECT * FROM container_items ORDER BY updatedAt DESC")
    fun getAllItemsWithContainers(): Flow<List<ItemWithContainer>>
}