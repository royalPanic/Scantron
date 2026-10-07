package com.example.scantron.sync

import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ExportImportManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the Kotlin client and the C# desktop agree on the live-sync wire, using the *desktop's own
 * bytes* rather than anything this codebase produced.
 *
 * The protocol has two independent implementations - `Scantron.Core/Sync/SyncWire.cs` and
 * `sync/SyncWire.kt` - written separately against the same documented contract. Each side's existing
 * tests only show that it agrees with *itself*: a renamed field, a re-nested object or a changed
 * casing passes both suites and then fails on a warehouse floor, where the symptom is two devices
 * that quietly refuse to talk rather than an assertion.
 *
 * Every constant below is therefore **verbatim output of `SyncWire.Write(...)`**, captured by running
 * a throwaway C# console program against `Scantron.Core`. The comment on each one names the call that
 * produced it. Nothing here is hand-written from memory, because a hand-written frame is exactly the
 * thing that would agree with the implementation being tested.
 *
 * Whitespace inside the captured JSON is not significant; the tokens are. Where a frame is long it is
 * laid out over several lines for readability and [String.trimIndent] removes this file's own margin.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWireDesktopParityTest {

    // ---- captured frames -------------------------------------------------------------------------

    /**
     * `SyncWire.Write(new SyncMessage { Type = Hello, Seq = 1, Src = "ck65-1",
     * Hello = new HelloInfo("ck65-1", "CK65-04", SyncMessage.ProtocolVersion) })`
     */
    private val desktopHello = """
        {"type":"hello","seq":1,"src":"ck65-1","hello":{"deviceId":"ck65-1","name":"CK65-04","protocolVersion":1}}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Pair, Seq = 2, Src = "ck65-1",
     * Pair = new PairInfo("4182-9930") })`
     */
    private val desktopPair = """
        {"type":"pair","seq":2,"src":"ck65-1","pair":{"code":"4182-9930"}}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Paired, Seq = 2, Src = "desktop",
     * Paired = new PairedInfo("ck65-1", "WAREHOUSE-DESK", "sess-7c1f9e") })`
     */
    private val desktopPaired = """
        {"type":"paired","seq":2,"src":"desktop","paired":{"deviceId":"ck65-1","desktopName":"WAREHOUSE-DESK","sessionId":"sess-7c1f9e"}}
    """.trimIndent()

    /** `SyncWire.Write(new SyncMessage { Type = Ack, Src = "desktop", AckSeq = 42 })` */
    private val desktopAck = """
        {"type":"ack","seq":0,"src":"desktop","ackSeq":42}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Bye, Src = "desktop",
     * Reason = "The handheld is running a different Scantron version." })`
     */
    private val desktopBye = """
        {"type":"bye","seq":0,"src":"desktop","reason":"The handheld is running a different Scantron version."}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Conflict, Seq = 3, Src = "desktop",
     * Conflict = new ConflictInfo("BOX-101/3f1c9e2a/Quantity", "BOX-101", "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
     * "Cordless Drill 18V", "Quantity", "1", "4", "9") })`
     */
    private val desktopConflict = """
        {"type":"conflict","seq":3,"src":"desktop","conflict":{"conflictId":"BOX-101/3f1c9e2a/Quantity","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","itemName":"Cordless Drill 18V","field":"Quantity","baseValue":"1","localValue":"4","remoteValue":"9"}}
    """.trimIndent()

    /**
     * `SyncWire.Write(... ConflictInfo("BOX-101/3f1c9e2a/Notes", "BOX-101", "3f1c9e2a-...", "Cordless Drill 18V",
     * "Notes", null, null, "2 lithium batteries"))` - the case where the field did not exist in the base
     * and the local side had no opinion. The desktop writes *explicit* JSON nulls, not omitted keys.
     */
    private val desktopConflictWithNullValues = """
        {"type":"conflict","seq":4,"src":"desktop","conflict":{"conflictId":"BOX-101/3f1c9e2a/Notes","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","itemName":"Cordless Drill 18V","field":"Notes","baseValue":null,"localValue":null,"remoteValue":"2 lithium batteries"}}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Resolve, Seq = 5, Src = "ck65-1",
     * Ops = [new ResolveOp("BOX-101", "3f1c9e2a-...", "Quantity", "9", 1791102143565)] })`
     */
    private val desktopResolve = """
        {"type":"resolve","seq":5,"src":"ck65-1","ops":[{"op":"resolve","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","field":"Quantity","value":"9","at":1791102143565}]}
    """.trimIndent()

    /**
     * `SyncWire.Write(... new ResolveOp("BOX-101", "3f1c9e2a-...", "Notes", null, 1791102143566))` - a resolve
     * that clears the field. The desktop writes `"value":null`; an omitted key would read back as the
     * *empty string* on a naive reader, which is a different instruction.
     */
    private val desktopResolveNullValue = """
        {"type":"resolve","seq":6,"src":"ck65-1","ops":[{"op":"resolve","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","field":"Notes","value":null,"at":1791102143566}]}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Changes, Seq = 7, Src = "desktop",
     * Ops = [new UpsertContainerOp(container)] })` where `container` was built **with items populated**
     * (one row). The captured bytes show the wire dropping them (`"items":null`) - rows travel as their
     * own ops so that a deletion can be expressed as a deletion rather than as an omission.
     *
     * The desktop's JSON writer also escapes `&` as `\u0026`; the Kotlin reader must decode that back to
     * the bare character the model holds.
     */
    private val desktopUpsertContainer = """
        {"type":"changes","seq":7,"src":"desktop","ops":[{"op":"upsertContainer","container":{"id":"BOX-101","name":"Power Tools \u0026 Hardware","location":"Garage Shelf 2-A","barcode":null,"notes":"Heavy plastic storage bin","updatedAt":1791102143565,"items":null}}]}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Changes, Seq = 8, Src = "ck65-1",
     * Ops = [new UpsertItemOp("BOX-101", item)] })` for an item carrying every field.
     */
    private val desktopUpsertItem = """
        {"type":"changes","seq":8,"src":"ck65-1","ops":[{"op":"upsertItem","containerId":"BOX-101","item":{"name":"Cordless Drill 18V","uuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","barcode":"088381699921","quantity":4,"category":"Tools","notes":"Includes 2 lithium batteries","updatedAt":1791102143565}}]}
    """.trimIndent()

    /**
     * `SyncWire.Write(... Type = Changes, Seq = 9, Src = "desktop",
     * Ops = [new UpsertContainerOp(container), new UpsertItemOp("BOX-101", item),
     * new ResolveOp("BOX-101", "3f1c9e2a-...", "Quantity", null, 1791102143567)])`
     *
     * A container and its rows in one batch: the container op must not carry the row, and the row must
     * arrive as its own op with the container id spelled the same way.
     */
    private val desktopChanges = """
        {"type":"changes","seq":9,"src":"desktop","ops":[{"op":"upsertContainer","container":{"id":"BOX-101","name":"Power Tools \u0026 Hardware","location":"Garage Shelf 2-A","barcode":null,"notes":"Heavy plastic storage bin","updatedAt":1791102143565,"items":null}},{"op":"upsertItem","containerId":"BOX-101","item":{"name":"Cordless Drill 18V","uuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","barcode":"088381699921","quantity":4,"category":"Tools","notes":"Includes 2 lithium batteries","updatedAt":1791102143565}},{"op":"resolve","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","field":"Quantity","value":null,"at":1791102143567}]}
    """.trimIndent()

    /**
     * `SyncWire.Write(new SyncMessage { Type = Snapshot, Src = "desktop", Ops = [new SnapshotOp(document)] })`
     * where `document` was a full `InventoryDocument` with two containers (one holding a row, one empty).
     *
     * The document body is byte-for-byte what `InventoryReader.Write` produces for a file - the desktop
     * embeds it with `WriteRawValue`, which is what keeps a live sync and a USB transfer interchangeable.
     */
    private val desktopSnapshot = """
        {"type":"snapshot","seq":0,"src":"desktop","ops":[{"op":"snapshot","document":{
          "app": "Scantron",
          "version": "1.1",
          "exportedAt": "2026-10-04T04:22:23Z",
          "containers": [
            {
              "id": "BOX-101",
              "name": "Power Tools \u0026 Hardware",
              "location": "Garage Shelf 2-A",
              "barcode": null,
              "notes": "Heavy plastic storage bin",
              "updatedAt": 1791102143565,
              "items": [
                {
                  "name": "Cordless Drill 18V",
                  "uuid": "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
                  "barcode": "088381699921",
                  "quantity": 4,
                  "category": "Tools",
                  "notes": "Includes 2 lithium batteries",
                  "updatedAt": 1791102143565
                }
              ]
            },
            {
              "id": "EMPTY-1",
              "name": "Awaiting stock",
              "location": "Loading dock",
              "barcode": null,
              "notes": "",
              "updatedAt": 1791102143565,
              "items": []
            }
          ]
        }}]}
    """.trimIndent()

    // ---- plumbing --------------------------------------------------------------------------------

    /**
     * Reads a captured desktop frame, failing loudly *with the frame* when the client refuses bytes the
     * authoritative writer produced - that refusal is the drift this file exists to catch.
     */
    private fun parse(frame: String): SyncMessage = when (val result = SyncJson.read(frame)) {
        is SyncJson.ReadResult.Parsed -> result.message
        is SyncJson.ReadResult.Invalid ->
            throw AssertionError("the client refused a frame the desktop wrote: ${result.reason}\n$frame")
    }

    private fun singleOp(frame: String): SyncOp {
        val ops = parse(frame).ops
        assertEquals("one op expected in\n$frame", 1, ops.size)
        return ops.single()
    }

    // ---- handshake frames ------------------------------------------------------------------------

    @Test
    fun `a captured hello frame survives with its device id, name and protocol version`() {
        val message = parse(desktopHello)

        assertEquals(SyncMessageType.Hello, message.type)
        assertEquals(1L, message.seq)
        assertEquals("ck65-1", message.src)
        assertEquals("ck65-1", message.hello?.deviceId)
        assertEquals("CK65-04", message.hello?.name)
        // Pinned against the constant, not against the literal 1: if the desktop ever refuses a newer
        // build, this file must fail rather than quietly accept a version the client cannot speak.
        assertEquals(SyncJson.PROTOCOL_VERSION, message.hello?.protocolVersion)
    }

    @Test
    fun `a captured pair frame carries the code the operator typed`() {
        val message = parse(desktopPair)

        assertEquals(SyncMessageType.Pair, message.type)
        assertEquals("4182-9930", message.pair?.code)
    }

    @Test
    fun `a captured paired frame carries the desktop name and the session id`() {
        val message = parse(desktopPaired)

        assertEquals(SyncMessageType.Paired, message.type)
        assertEquals("ck65-1", message.paired?.deviceId)
        assertEquals("WAREHOUSE-DESK", message.paired?.desktopName)
        assertEquals("sess-7c1f9e", message.paired?.sessionId)
    }

    @Test
    fun `a captured ack frame reports the sequence the desktop applied durably`() {
        val message = parse(desktopAck)

        assertEquals(SyncMessageType.Ack, message.type)
        assertEquals(42L, message.ackSeq)
        assertTrue("an ack carries no ops", message.ops.isEmpty())
    }

    @Test
    fun `a captured bye frame keeps the sentence shown to the operator`() {
        val message = parse(desktopBye)

        assertEquals(SyncMessageType.Bye, message.type)
        // The reason is the whole point of the frame: a version mismatch has to reach the status line as
        // a sentence, so losing it turns a diagnosable failure into a dead connection.
        assertEquals("The handheld is running a different Scantron version.", message.reason)
    }

    // ---- conflict frame --------------------------------------------------------------------------

    @Test
    fun `a captured conflict frame carries all eight fields`() {
        val conflict = parse(desktopConflict).conflict

        assertEquals("BOX-101/3f1c9e2a/Quantity", conflict?.conflictId)
        assertEquals("BOX-101", conflict?.containerId)
        assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", conflict?.itemUuid)
        assertEquals("Cordless Drill 18V", conflict?.itemName)
        assertEquals("Quantity", conflict?.field)
        assertEquals("1", conflict?.baseValue)
        assertEquals("4", conflict?.localValue)
        assertEquals("9", conflict?.remoteValue)
    }

    @Test
    fun `a conflict whose value is an explicit null reads back as null and not as the text null`() {
        val conflict = parse(desktopConflictWithNullValues).conflict

        // "the field did not exist in the base" and "the peer had no opinion" are both real answers, and
        // both are distinct from the empty string. A reader that used optString without a null check
        // would hand the UI the literal word "null" here and an operator would see it.
        assertNull(conflict?.baseValue)
        assertNull(conflict?.localValue)
        assertEquals("2 lithium batteries", conflict?.remoteValue)
    }

    // ---- resolve op ------------------------------------------------------------------------------

    @Test
    fun `a captured resolve op carries the chosen value and the container it belongs to`() {
        val op = singleOp(desktopResolve)

        val resolve = singleOp(desktopResolve) as ResolveOp

        assertEquals("BOX-101", resolve.containerId)
        assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", resolve.itemUuid)
        assertEquals("Quantity", resolve.field)
        assertEquals("9", resolve.value)
        assertEquals(1791102143565L, resolve.at)
    }

    @Test
    fun `a captured resolve op with an explicit null value reads back as null`() {
        val resolve = singleOp(desktopResolveNullValue) as ResolveOp

        // Clearing a field is a decision, not an absence: reading this as "" would tell the desktop the
        // operator typed an empty string, which is a different edit and would not clear anything.
        assertNull(resolve.value)
        assertEquals("Notes", resolve.field)
        assertEquals(1791102143566L, resolve.at)
    }

    // ---- change frames ---------------------------------------------------------------------------

    @Test
    fun `a captured container upsert arrives with its metadata and deliberately no rows`() {
        val op = singleOp(desktopUpsertContainer) as UpsertContainerOp

        assertEquals("BOX-101", op.container.id)
        assertEquals("Power Tools & Hardware", op.container.name)
        assertEquals("Garage Shelf 2-A", op.container.location)
        assertEquals("Heavy plastic storage bin", op.container.notes)
        assertEquals(1791102143565L, op.container.updatedAt)

        // The source container held a row and the wire dropped it. Asserting on the raw bytes as well as
        // on the parsed op is what makes that a pinned fact rather than an accident of the Kotlin model.
        val containerJson = JSONObject(desktopUpsertContainer)
            .getJSONArray("ops")
            .getJSONObject(0)
            .getJSONObject("container")
        assertTrue(
            "a container upsert must not carry rows: omission cannot express a delete",
            !containerJson.has("items") || containerJson.isNull("items"),
        )
    }

    @Test
    fun `a captured row upsert survives with every field of the row`() {
        val op = singleOp(desktopUpsertItem) as UpsertItemOp

        assertEquals("BOX-101", op.containerId)
        assertEquals("Cordless Drill 18V", op.item.name)
        assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", op.item.uuid)
        assertEquals("088381699921", op.item.barcode)
        assertEquals(4, op.item.quantity)
        assertEquals("Tools", op.item.category)
        assertEquals("Includes 2 lithium batteries", op.item.notes)
        assertEquals(1791102143565L, op.item.updatedAt)
    }

    @Test
    fun `a captured batch keeps its ops in order with the row separated from its container`() {
        val ops = parse(desktopChanges).ops

        assertEquals(3, ops.size)
        assertEquals(SyncOpKind.UpsertContainer, ops[0].kind)
        assertEquals(SyncOpKind.UpsertItem, ops[1].kind)
        assertEquals(SyncOpKind.Resolve, ops[2].kind)

        // The row must be addressed by the same tag the container op used, or the receiver would file it
        // under a container that does not exist.
        val container = ops[0] as UpsertContainerOp
        val row = ops[1] as UpsertItemOp
        assertEquals(container.container.id, row.containerId)
    }

    // ---- snapshot frame --------------------------------------------------------------------------

    @Test
    fun `a captured snapshot still reads as a whole Scantron export document`() {
        val message = parse(desktopSnapshot)

        assertEquals(SyncMessageType.Snapshot, message.type)
        val op = message.ops.single() as SnapshotOp

        // Parsed through the *same* reader a USB import uses, which is the property that keeps the two
        // transports interchangeable. A snapshot the device cannot import is a first sync that can never
        // succeed.
        val document = ExportImportManager.parseDocument(op.json)
        assertEquals(2, document.containers.size)
        assertEquals(1, document.items.size)

        val box = document.container("BOX-101")
        assertEquals("Power Tools & Hardware", box?.name)
        assertEquals("Garage Shelf 2-A", box?.location)
        assertEquals(1791102143565L, box?.updatedAt)

        val row = document.items.single()
        assertEquals("BOX-101", row.containerId)
        assertEquals("Cordless Drill 18V", row.name)
        assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", row.uuid)
        assertEquals("088381699921", row.barcode)
        assertEquals(4, row.quantity)
        assertEquals("Tools", row.category)
        assertEquals("Includes 2 lithium batteries", row.notes)
        assertEquals(1791102143565L, row.updatedAt)

        assertEquals("the empty container must survive as an empty container", 0, document.itemsOf("EMPTY-1").size)
    }

    @Test
    fun `a captured snapshot document declares version 1_1 and the Scantron app id`() {
        val message = parse(desktopSnapshot)
        val snapshot = message.ops.single() as SnapshotOp

        // Asserted on the literal desktop bytes rather than on the parse result, because "which version
        // does the peer claim" is the field that decides whether a 1.0-era client may import at all.
        val version = JSONObject(snapshot.json).getString("version")
        assertEquals("1.1", version)
        assertEquals("Scantron", JSONObject(snapshot.json).getString("app"))
    }

    // ---- the other direction ---------------------------------------------------------------------

    /**
     * This build's own `SyncJson.write(...)` output for the logical message above, captured from a test
     * run and then fed to the **real desktop parser** - `SyncWire.TryRead` accepted it and read every
     * field back, including the explicit null on the resolve value.
     *
     * Pinned as a literal because that hand-check cannot be repeated from here: if the client's writer
     * changes, the desktop acceptance it was verified against is no longer known to hold, and this test
     * failing is the reminder to re-run the check rather than a defect in itself.
     */
    private val androidChangesBytes = """
        {"type":"changes","seq":12,"src":"ck65-1","ops":[{"op":"upsertContainer","container":{"id":"BOX-101","name":"Power Tools & Hardware","location":"Garage Shelf 2-A","notes":"Heavy plastic storage bin","updatedAt":1791102143565}},{"op":"upsertItem","containerId":"BOX-101","item":{"name":"Cordless Drill 18V","uuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","barcode":"088381699921","quantity":4,"category":"Tools","notes":"Includes 2 lithium batteries","updatedAt":1791102143565}},{"op":"resolve","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","field":"Quantity","value":null,"at":1791102143567}]}
    """.trimIndent()

    private fun containerOp() = UpsertContainerOp(
        Container(
            id = "BOX-101",
            name = "Power Tools & Hardware",
            location = "Garage Shelf 2-A",
            notes = "Heavy plastic storage bin",
            updatedAt = 1791102143565L,
        ),
    )

    private fun rowOp() = UpsertItemOp(
        containerId = "BOX-101",
        item = ContainerItem(
            containerId = "BOX-101",
            name = "Cordless Drill 18V",
            barcode = "088381699921",
            quantity = 4,
            category = "Tools",
            notes = "Includes 2 lithium batteries",
            uuid = "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
            updatedAt = 1791102143565L,
        ),
    )

    @Test
    fun `the client writes the desktop's captured hello frame back byte for byte`() {
        // The whole frame, not a field at a time: read the desktop's bytes, write them out again, and the
        // two implementations have to have agreed on the key names, the nesting *and* the spelling of the
        // message type for this to hold.
        val fromDesktop = parse(desktopHello)

        assertEquals(desktopHello, SyncJson.write(fromDesktop))
    }

    @Test
    fun `the client's outbound batch matches the desktop's, and is the batch the desktop accepted`() {
        val written = SyncJson.write(
            SyncMessage(
                type = SyncMessageType.Changes,
                seq = 12,
                src = "ck65-1",
                ops = listOf(
                    containerOp(),
                    rowOp(),
                    ResolveOp("BOX-101", "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", "Quantity", null, 1791102143567L),
                ),
            ),
        )

        val androidOps = parse(written).ops
        val desktopOps = parse(desktopChanges).ops
        assertEquals(desktopOps.size, androidOps.size)

        // Compared as parsed ops rather than as raw text, because a container op is allowed to differ in
        // one cosmetic way (the next test pins it) that must not be allowed to hide a difference that
        // matters. The data classes are values, so equality here means every field agreed.
        assertEquals(desktopOps[1], androidOps[1])
        assertEquals(desktopOps[2], androidOps[2])

        // These are the bytes the real desktop parser was handed, and it read every field back.
        assertEquals(androidChangesBytes, written)
    }

    @Test
    fun `the one field-level difference between the two container writers is the null placeholders`() {
        val androidContainer = JSONObject(SyncJson.write(SyncMessage(
            type = SyncMessageType.Changes,
            seq = 7,
            src = "desktop",
            ops = listOf(containerOp()),
        ))).getJSONArray("ops").getJSONObject(0).getJSONObject("container")

        val desktopContainer = JSONObject(desktopUpsertContainer)
            .getJSONArray("ops").getJSONObject(0).getJSONObject("container")

        // Everything the client writes is identical to the desktop's, token for token.
        for (name in listOf("id", "name", "location", "notes", "updatedAt")) {
            assertEquals("container.$name drifted", desktopContainer.get(name), androidContainer.get(name))
        }

        // The desktop's writer emits two extra *explicit nulls* - the `barcode` placeholder and the
        // `items` list it deliberately empties - that the client's writer omits entirely. Both readers
        // treat an absent key as null, so this is tolerated rather than fatal; it is asserted here so the
        // asymmetry is a pinned fact instead of a surprise the next time the two container shapes are
        // compared by eye. If the client ever starts emitting them, this test failing is the signal that
        // the desktop-side check needs redoing, not a bug in the client.
        assertFalse(androidContainer.has("barcode"))
        assertFalse(androidContainer.has("items"))
        assertTrue(desktopContainer.isNull("barcode"))
        assertTrue(desktopContainer.isNull("items"))
    }

    @Test
    fun `a captured desktop frame survives a trip out through the client writer and back`() {
        val fromDesktop = parse(desktopChanges)

        val reread = parse(SyncJson.write(fromDesktop))

        // Both directions of the same vector: the client read the desktop's bytes and then produced bytes
        // it could read back to an identical message, so nothing was lost in either translation.
        assertEquals(fromDesktop, reread)
    }
}
