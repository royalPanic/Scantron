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

    @Query("SELECT COUNT(*) FROM container_items WHERE containerId = :containerId")
    fun getItemCountForContainer(containerId: String): Flow<Int>

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