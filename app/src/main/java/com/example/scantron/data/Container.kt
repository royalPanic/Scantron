package com.example.scantron.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "containers")
data class Container(
    @PrimaryKey
    val id: String, // E.g., "BIN-01", "BOX-102", "CONTAINER-A"
    val name: String = "", // Optional friendly title e.g. "Garage Power Tools"
    val location: String = "", // E.g., "Shelf 2, Top Right"
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
