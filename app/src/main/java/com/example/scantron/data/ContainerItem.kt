package com.example.scantron.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "container_items",
    foreignKeys = [
        ForeignKey(
            entity = Container::class,
            parentColumns = ["id"],
            childColumns = ["containerId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("containerId"), Index("barcode")]
)
data class ContainerItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val containerId: String,
    val name: String,
    val barcode: String = "", // Optional barcode / SKU / UPC / QR code string
    val quantity: Int = 1,
    val category: String = "",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
