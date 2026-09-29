package com.example.scantron.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerDao {
    @Query("SELECT * FROM containers ORDER BY updatedAt DESC")
    fun getAllContainers(): Flow<List<Container>>

    @Query("SELECT * FROM containers WHERE id = :containerId")
    fun getContainerByIdFlow(containerId: String): Flow<Container?>

    @Query("SELECT * FROM containers WHERE id = :containerId")
    suspend fun getContainerById(containerId: String): Container?

    @Query("SELECT * FROM containers WHERE id LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%' OR location LIKE '%' || :query || '%' ORDER BY id ASC")
    fun searchContainers(query: String): Flow<List<Container>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContainer(container: Container)

    @Update
    suspend fun updateContainer(container: Container)

    @Delete
    suspend fun deleteContainer(container: Container)
}
