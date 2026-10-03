package com.example.scantron.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * The containers list shows a single figure per container, and it has to be the number of
 * units held rather than the number of distinct item rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ContainerQuantityTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ContainerRepository

    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        repository = ContainerRepository(database.containerDao(), database.itemDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an empty container reports zero`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))

        assertEquals(0, repository.getTotalQuantity("BOX-101").first())
    }

    @Test
    fun `a single unit reports one`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Drill", quantity = 1))

        assertEquals(1, repository.getTotalQuantity("BOX-101").first())
    }

    @Test
    fun `quantities are summed rather than rows counted`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Screws", quantity = 12))
        repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Tape", quantity = 3))
        repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Wrench", quantity = 1))

        // Three distinct rows, sixteen units.
        assertEquals(16, repository.getTotalQuantity("BOX-101").first())
    }

    @Test
    fun `each container is totalled independently`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        repository.createOrUpdateContainer(Container(id = "BIN-42"))
        repository.saveItem(ContainerItem(containerId = "BOX-101", name = "Screws", quantity = 12))
        repository.saveItem(ContainerItem(containerId = "BIN-42", name = "Tent", quantity = 2))

        assertEquals(12, repository.getTotalQuantity("BOX-101").first())
        assertEquals(2, repository.getTotalQuantity("BIN-42").first())
    }

    @Test
    fun `the total tracks later quantity edits`() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101"))
        val screws = ContainerItem(
            containerId = "BOX-101",
            name = "Screws",
            barcode = "076808001021",
            quantity = 1,
        )
        repository.addItemMerging(screws)
        repository.addItemMerging(screws.copy(id = 0L, quantity = 4))

        assertEquals(5, repository.getTotalQuantity("BOX-101").first())
    }
}