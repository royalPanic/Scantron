package com.example.scantron

import android.app.Application
import com.example.scantron.data.AppDatabase
import com.example.scantron.data.ContainerRepository

class ScantronApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val repository: ContainerRepository by lazy {
        ContainerRepository(database.containerDao(), database.itemDao())
    }
}
