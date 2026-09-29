package com.example.scantron.data

import androidx.room.Embedded
import androidx.room.Relation

data class ItemWithContainer(
    @Embedded val item: ContainerItem,
    @Relation(
        parentColumn = "containerId",
        entityColumn = "id"
    )
    val container: Container
)
