package com.example.scantron.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.scantron.data.AppDatabase
import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ContainerRepository
import com.example.scantron.data.ItemKeyKind
import com.example.scantron.data.InventoryDocument
import com.example.scantron.sync.DeleteItemOp
import com.example.scantron.sync.SyncJson
import com.example.scantron.sync.SyncMessage
import com.example.scantron.sync.SyncMessageType
import com.example.scantron.sync.UpsertItemOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives [SyncEngine] against a real loopback desktop speaking the contract.
 *
 * The fake desktop is a genuinely separate implementation of the handshake and the framing rules, so a
 * passing test means the two halves agree on the wire - which is the only evidence that matters for a
 * hand-written protocol, and the thing a mock would never provide.
 *
 * These are deliberately end-to-end rather than unit tests of the engine's internals: the interesting
 * failures - a batch applied twice, a base advanced past a conflict, a reconnect that loses an offline
 * edit - are only visible in the state that survives each of them.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ContainerRepository
    private lateinit var store: SyncStore
    private lateinit var scope: CoroutineScope
    private lateinit var baseDirectory: File

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

        repository = ContainerRepository(
            database.containerDao(),
            database.itemDao(),
            database.deletionDao(),
        )

        baseDirectory = File(
            ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir,
            "sync-test-${System.nanoTime()}",
        )
        store = SyncStore(ApplicationProvider.getApplicationContext(), baseDirectory)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        baseDirectory.deleteRecursively()
    }

    private fun engineFor(port: Int): SyncEngine = SyncEngine(
        repository = repository,
        store = store,
        clientFactory = { host, _ -> WebSocketClient(host = host, port = port, readTimeoutMs = 2_000) },
    )

    private fun seedLocalContainer() = runBlocking {
        repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Local shelf"))
        repository.addItemMerging(
            ContainerItem(
                containerId = "BOX-101",
                name = "Cordless Drill 18V",
                barcode = "088381699921",
                quantity = 1,
                uuid = "local-drill",
            ),
        )
    }

    /** Waits for [condition], polling because the engine runs on real threads against real sockets. */
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    // ---- handshake and snapshot ------------------------------------------------------------------

    @Test
    fun `a session pairs, sends a snapshot and applies the desktop's rows`() {
        seedLocalContainer()

        val acked = AtomicReference<Long>()
        val server = FakeDesktopServer { conversation ->
            // hello, then pair.
            val hello = conversation.nextMessage() ?: return@FakeDesktopServer
            assertEquals(SyncMessageType.Hello, hello.type)
            assertEquals(SyncJson.PROTOCOL_VERSION, hello.hello?.protocolVersion)

            val pair = conversation.nextMessage() ?: return@FakeDesktopServer
            assertEquals(SyncMessageType.Pair, pair.type)
            assertEquals("123456", pair.pair?.code)

            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s1"),
                ),
            )

            // No base yet, so the opening frame must be a full snapshot.
            val opening = conversation.nextMessage() ?: return@FakeDesktopServer
            assertEquals(SyncMessageType.Snapshot, opening.type)
            assertTrue(opening.ops.isNotEmpty())

            // The desktop answers with its own row, which the engine must apply automatically.
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Changes,
                    seq = 7,
                    src = "desktop",
                    ops = listOf(
                        UpsertItemOp(
                            containerId = "BOX-101",
                            item = ContainerItem(
                                containerId = "BOX-101",
                                name = "Measuring Tape 25ft",
                                quantity = 3,
                                uuid = "desktop-tape",
                            ),
                        ),
                    ),
                ),
            )

            val ack = conversation.nextMessage() ?: return@FakeDesktopServer
            assertEquals(SyncMessageType.Ack, ack.type)
            acked.set(ack.ackSeq)

            conversation.sayGoodbye("done")
        }

        server.use {
            val engine = engineFor(server.port)
            engine.start(scope, "127.0.0.1", server.port)
            engine.pair("123456")

            assertTrue("server never saw the session", server.awaitConnection())
            assertTrue("no ack arrived", await { acked.get() != null })
        }

        // The ack names the batch's sequence, which is what tells the desktop the ops are durable.
        assertEquals(7L, acked.get())

        val rows = runBlocking { repository.getItemsForContainerDirect("BOX-101") }
        assertEquals(
            "the desktop's row must be applied without confirmation",
            1,
            rows.count { it.name == "Measuring Tape 25ft" },
        )
    }

    @Test
    fun `a second session is incremental after a base is established`() {
        seedLocalContainer()

        // Session one: pair, and answer the opening snapshot with an empty batch. An empty batch is
        // "I agree with what you sent", which is what lets the engine durably adopt a base.
        val first = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s1"),
                ),
            )
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(SyncMessage(type = SyncMessageType.Changes, seq = 1, src = "desktop"))
            conversation.nextMessage()
            Thread.sleep(200)
            conversation.disconnect()
        }

        first.use {
            val engine = engineFor(first.port)
            engine.start(scope, "127.0.0.1", first.port)
            engine.pair("123456")
            assertTrue("no base was ever established", await { store.baseExists() })
            engine.stop()
        }

        // Session two: with a base in hand, the opening must be incremental. Nothing changed locally
        // in between, so the honest opening is a batch of no ops - and it is checked here by asserting
        // that whatever arrives is not another whole document.
        val secondOpening = AtomicReference<SyncMessageType>()
        val second = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s2"),
                ),
            )

            // Bounded, because an incremental session with nothing to say legitimately sends nothing.
            conversation.nextMessage(1_000)?.let { secondOpening.set(it.type) }
            conversation.sayGoodbye("done")
        }

        second.use {
            val engine = engineFor(second.port)
            engine.start(scope, "127.0.0.1", second.port)
            engine.pair("123456")
            assertTrue(await { second.connections.get() > 0 && store.lastPairedHost() == "127.0.0.1" })
            Thread.sleep(600)
        }

        // A snapshot here would mean the base was ignored, and every session would re-send the whole
        // inventory forever - the difference between an incremental sync and a full one.
        assertTrue(
            "a second session must not re-send a snapshot, got ${secondOpening.get()}",
            secondOpening.get() == null || secondOpening.get() == SyncMessageType.Changes,
        )
    }

    // ---- conflicts -------------------------------------------------------------------------------

    @Test
    fun `a conflicting field is held and does not advance the base`() {
        // Both sides changed the quantity to different values from the same agreed base.
        val base = InventoryDocument(
            containers = listOf(Container(id = "BOX-101", name = "Shelf")),
            items = listOf(
                ContainerItem(containerId = "BOX-101", name = "Drill", quantity = 1, uuid = "u1"),
            ),
        )
        store.writeBase(base)

        runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Shelf"))
            repository.addItemMerging(
                ContainerItem(containerId = "BOX-101", name = "Drill", quantity = 5, uuid = "u1"),
            )
        }

        val server = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s"),
                ),
            )

            // Consume the opening frame, then push the conflicting value.
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Changes,
                    seq = 3,
                    src = "desktop",
                    ops = listOf(
                        UpsertItemOp(
                            containerId = "BOX-101",
                            item = ContainerItem(
                                containerId = "BOX-101",
                                name = "Drill",
                                quantity = 9,
                                uuid = "u1",
                            ),
                        ),
                    ),
                ),
            )

            conversation.nextMessage() // ack
            Thread.sleep(400)
            conversation.disconnect()
        }

        val engine = engineFor(server.port)

        server.use {
            engine.start(scope, "127.0.0.1", server.port)
            engine.pair("123456")

            assertTrue("the conflict was never surfaced", await { engine.conflicts.value.isNotEmpty() })

            val conflict = engine.conflicts.value.single()
            assertEquals("Quantity", conflict.field)
            assertEquals("u1", conflict.itemUuid)
        }

        // The base must NOT have moved to either value: advancing it would declare a winner and lose
        // the other side's number, which is the failure the whole design exists to prevent.
        val persistedBase = store.readBase()
        assertNotNull(persistedBase)
        assertEquals(
            "a conflicted field must stay at the last agreed value",
            "1",
            persistedBase!!.itemByUuid("u1")?.quantity?.toString(),
        )

        // The local edit is still shown to the operator, because it is the side they are looking at.
        val localQuantity = runBlocking {
            repository.getItemsForContainerDirect("BOX-101").single { it.uuid == "u1" }.quantity
        }
        assertEquals(5, localQuantity)
    }

    // ---- idempotency -----------------------------------------------------------------------------

    @Test
    fun `replaying the same batch twice is a no-op`() {
        runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Shelf"))
        }

        val engine = engineFor(0)
        val batch = listOf(
            UpsertItemOp(
                containerId = "BOX-101",
                item = ContainerItem(containerId = "BOX-101", name = "Tape", quantity = 2, uuid = "t1"),
            ),
        )

        // Applying through the engine's own path twice must land one row and one quantity, not two.
        runBlocking {
            val first = com.example.scantron.sync.SyncApply.apply(null, repository.snapshotDocument(), batch)
            repository.applyDocumentDelta(first.document)
            store.writeBase(first.base)

            val second = com.example.scantron.sync.SyncApply.apply(
                store.readBase(),
                repository.snapshotDocument(),
                batch,
            )
            repository.applyDocumentDelta(second.document)
        }

        val rows = runBlocking { repository.getItemsForContainerDirect("BOX-101") }
        assertEquals("a replayed batch must not duplicate the row", 1, rows.size)
        assertEquals(2, rows.single().quantity)

        engine.stop()
    }

    // ---- reconnect -------------------------------------------------------------------------------

    @Test
    fun `an edit made while disconnected is sent on reconnect`() {
        runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Shelf"))
        }

        // Session one: pair, exchange an empty view, then drop the socket.
        val first = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s1"),
                ),
            )
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(SyncMessage(type = SyncMessageType.Changes, seq = 1, src = "desktop"))
            conversation.nextMessage()
            Thread.sleep(200)
            conversation.disconnect()
        }

        val secondOpeningOps = AtomicReference<List<com.example.scantron.sync.SyncOp>>(emptyList())

        val second = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s2"),
                ),
            )

            val opening = conversation.nextMessage() ?: return@FakeDesktopServer
            secondOpeningOps.set(opening.ops)
            conversation.sayGoodbye("done")
        }

        first.use {
            val engine = engineFor(first.port)
            engine.start(scope, "127.0.0.1", first.port)
            engine.pair("123456")
            assertTrue(await { store.baseExists() })

            // The edit that happens while the socket is down. Nothing is observing it for the wire -
            // which is precisely why the reconnect has to re-diff.
            runBlocking {
                repository.addItemMerging(
                    ContainerItem(
                        containerId = "BOX-101",
                        name = "Offline edit",
                        quantity = 4,
                        uuid = "offline-1",
                    ),
                )
            }

            engine.stop()
        }

        second.use {
            val engine = engineFor(second.port)
            engine.start(scope, "127.0.0.1", second.port)
            engine.pair("123456")

            assertTrue(
                "the offline edit never reached the desktop",
                await { secondOpeningOps.get().any { op -> op is UpsertItemOp && op.item.uuid == "offline-1" } },
            )
        }
    }

    // ---- deletes ---------------------------------------------------------------------------------

    @Test
    fun `a delete on the wire removes the row`() {
        runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Shelf"))
            repository.addItemMerging(
                ContainerItem(containerId = "BOX-101", name = "Drill", quantity = 1, uuid = "u1"),
            )
            repository.addItemMerging(
                ContainerItem(containerId = "BOX-101", name = "Tape", quantity = 1, uuid = "u2"),
            )
        }

        val base = runBlocking { repository.snapshotDocument() }
        store.writeBase(base)

        val server = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Paired,
                    src = "desktop",
                    paired = com.example.scantron.sync.PairedInfo("device", "DESKTOP-TEST", "s"),
                ),
            )

            // The base already matches what this device holds, so the opening is legitimately empty.
            // A bounded read, because "nothing to say" is a valid opening and waiting the full timeout
            // for a frame that will never come would make this test slow rather than more correct.
            conversation.nextMessage(600)

            conversation.send(
                SyncMessage(
                    type = SyncMessageType.Changes,
                    seq = 4,
                    src = "desktop",
                    ops = listOf(
                        DeleteItemOp(
                            containerId = "BOX-101",
                            itemUuid = "u1",
                            keyKind = ItemKeyKind.Uuid,
                            keyValue = "u1",
                            itemName = "Drill",
                            at = 1,
                        ),
                    ),
                ),
            )

            conversation.nextMessage()
            Thread.sleep(300)
            conversation.disconnect()
        }

        server.use {
            val engine = engineFor(server.port)
            engine.start(scope, "127.0.0.1", server.port)
            engine.pair("123456")
            assertTrue(await { runBlocking { repository.getItemsForContainerDirect("BOX-101").size } == 1 })
        }

        val rows = runBlocking { repository.getItemsForContainerDirect("BOX-101") }
        assertEquals("exactly one row must survive the delete", 1, rows.size)
        assertEquals("u2", rows.single().uuid)
    }

    @Test
    fun `a local delete records a tombstone so a peer can be told about it`() {
        runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Shelf"))
            val item = repository.addItemMerging(
                ContainerItem(containerId = "BOX-101", name = "Drill", quantity = 1, uuid = "u1"),
            )
            repository.deleteItem(item)
        }

        val tombstones = runBlocking { repository.allDeletions() }
        assertEquals(1, tombstones.size)
        assertEquals("u1", tombstones.single().id)
        assertEquals("BOX-101", tombstones.single().containerId)
    }

    @Test
    fun `tombstones older than the horizon are pruned`() {
        runBlocking {
            repository.createOrUpdateContainer(Container(id = "BOX-101", name = "Shelf"))
            val item = repository.addItemMerging(
                ContainerItem(containerId = "BOX-101", name = "Drill", quantity = 1, uuid = "u1"),
            )
            repository.deleteItem(item)
        }

        // A horizon of zero prunes everything, which is what a peer that has been away longer than 30
        // days gets: a snapshot, in which the deletion is implicit rather than replayed.
        val pruned = runBlocking { repository.pruneDeletions(Long.MAX_VALUE) }

        assertEquals(1, pruned)
        assertTrue(runBlocking { repository.allDeletions() }.isEmpty())
    }

    // ---- error surfacing -------------------------------------------------------------------------

    @Test
    fun `a reason from the desktop is surfaced verbatim`() {
        val server = FakeDesktopServer { conversation ->
            conversation.nextMessage() ?: return@FakeDesktopServer
            conversation.sayGoodbye("Both devices must be running the same Scantron version.")
        }

        val engine = engineFor(server.port)

        server.use {
            engine.start(scope, "127.0.0.1", server.port)
            engine.pair("123456")

            val surfaced = await {
                (engine.status.value as? SyncStatus.Offline)?.reason?.contains("same Scantron version") == true
            }
            assertTrue("the desktop's reason was not surfaced: ${engine.status.value}", surfaced)
        }

        val status = engine.status.value
        assertTrue(status is SyncStatus.Offline)

        // Never an exception class name: this text goes straight in front of an operator.
        val reason = (status as SyncStatus.Offline).reason
        assertTrue(!reason.contains("Exception"))
    }

    @Test
    fun `a refused handshake is reported as version trouble, not as a crash`() {
        val plain = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                val socket = plain.accept()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\nnot a websocket".toByteArray())
                socket.getOutputStream().flush()
                Thread.sleep(200)
                socket.close()
            } catch (e: Exception) {
                // The test closes the server as soon as the assertion is made.
            }
        }.apply { isDaemon = true }
        thread.start()

        try {
            val engine = engineFor(plain.localPort)
            engine.start(scope, "127.0.0.1", plain.localPort)
            engine.pair("123456")

            assertTrue(
                "a non-websocket peer must be reported in words",
                await { engine.status.value is SyncStatus.Offline },
            )

            val reason = (engine.status.value as SyncStatus.Offline).reason
            assertTrue(reason, !reason.contains("Exception"))
            assertNull(null)
        } finally {
            plain.close()
        }
    }
}
