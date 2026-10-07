package com.example.scantron.sync

import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.InventoryDocument
import com.example.scantron.data.ItemKeyKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wire parity for the *deletion* ops, pinned against bytes the desktop actually wrote.
 *
 * Every JSON constant below is verbatim output of `Scantron.Core.Sync.SyncWire.Write(...)` from a
 * throwaway console harness that referenced `Scantron.Core.csproj`; the comment on each names the
 * exact call. They are checked in rather than generated at test time so this runs without a .NET
 * toolchain, exactly as [com.example.scantron.data.DesktopWireCompatibilityTest] does for the export
 * document.
 *
 * A deletion is the most dangerous op to get wrong, and it is the only op that carries a merge *key*
 * as well as a uuid. Two things can silently drift between the two implementations and neither shows
 * up as a compile error:
 *
 *  - `keyKind` is the C# enum's own `ToString()`, so the wire spelling is PascalCase (`Uuid`,
 *    `ContainerBarcode`, `NameOnly`) and is parsed case-insensitively on read. A rename in either
 *    language changes the contract.
 *  - `keyValue` for the heuristic kinds is `containerId + '\0' + discriminator`. NUL cannot appear in
 *    JSON text, so it travels as the six-character escape `\u0000` and has to survive escaping on
 *    both sides. If it does not, the receiver recomputes a different key and the row the operator
 *    deleted comes back on the next sync.
 *
 * The sibling parity test covers the non-delete message kinds; this file deliberately stays on
 * `deleteContainer` / `deleteItem` so the two do not overlap.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWireDeleteParityTest {

    // ---- captured desktop frames -----------------------------------------------------------------
    //
    // These are raw (triple-quoted) strings on purpose: a raw string performs no escape processing,
    // so `\u0000` here is the literal six-character JSON escape the desktop wrote, not a Kotlin NUL.
    // That is the whole point of the test - the NUL must be escaped on the wire and only become a
    // real NUL after parsing.

    /** `SyncWire.Write(new SyncMessage { Type = Changes, Seq = 7, Src = "desktop-1", Ops = [new DeleteContainerOp("BOX-101", 1730000000000L)] })` */
    private val desktopDeleteContainer =
        """{"type":"changes","seq":7,"src":"desktop-1","ops":[{"op":"deleteContainer","containerId":"BOX-101","at":1730000000000}]}"""

    /** `SyncWire.Write(... Ops = [new DeleteItemOp("BOX-101", "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", ItemKeyKind.Uuid, "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", "Cordless Drill 18V", 1730000000001L)])` */
    private val desktopDeleteItemUuid =
        """{"type":"changes","seq":8,"src":"desktop-1","ops":[{"op":"deleteItem","containerId":"BOX-101","itemUuid":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","keyKind":"Uuid","keyValue":"3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83","itemName":"Cordless Drill 18V","at":1730000000001}]}"""

    /** `SyncWire.Write(... Ops = [new DeleteItemOp("BOX-101", "", ItemKeyKind.ContainerBarcode, "BOX-101\0BBB", "Tape", 1730000000002L)])` */
    private val desktopDeleteItemContainerBarcode =
        """{"type":"changes","seq":9,"src":"desktop-1","ops":[{"op":"deleteItem","containerId":"BOX-101","itemUuid":"","keyKind":"ContainerBarcode","keyValue":"BOX-101\u0000BBB","itemName":"Tape","at":1730000000002}]}"""

    /** `SyncWire.Write(... Ops = [new DeleteItemOp("BOX-101", "", ItemKeyKind.NameOnly, "BOX-101\0unlabelled spare", "Unlabelled spare", 1730000000003L)])` */
    private val desktopDeleteItemNameOnly =
        """{"type":"changes","seq":10,"src":"desktop-1","ops":[{"op":"deleteItem","containerId":"BOX-101","itemUuid":"","keyKind":"NameOnly","keyValue":"BOX-101\u0000unlabelled spare","itemName":"Unlabelled spare","at":1730000000003}]}"""

    /** `SyncWire.Write(... Ops = [new DeleteItemOp("BOX-101", "", ItemKeyKind.ContainerBarcode, "BOX-101\0LEGACY-9", "Legacy row", 1730000000004L)])` */
    private val desktopDeleteItemLegacyNoUuid =
        """{"type":"changes","seq":11,"src":"desktop-1","ops":[{"op":"deleteItem","containerId":"BOX-101","itemUuid":"","keyKind":"ContainerBarcode","keyValue":"BOX-101\u0000LEGACY-9","itemName":"Legacy row","at":1730000000004}]}"""

    /**
     * Hand-written, not captured: the desktop always writes PascalCase, but its reader uses
     * `Enum.TryParse(..., ignoreCase: true)`, so a peer that spelled the kind differently must still
     * be understood. This pins the Android reader to the same tolerance.
     */
    private val lowercasedKeyKindFrame =
        """{"type":"changes","seq":12,"src":"desktop-1","ops":[{"op":"deleteItem","containerId":"BOX-101","itemUuid":"","keyKind":"containerbarcode","keyValue":"BOX-101\u0000BBB","itemName":"Tape","at":1730000000005}]}"""

    private fun parse(json: String): SyncMessage =
        when (val result = SyncJson.read(json)) {
            is SyncJson.ReadResult.Parsed -> result.message
            is SyncJson.ReadResult.Invalid -> throw AssertionError("Android rejected a desktop frame: ${result.reason}")
        }

    private fun onlyOp(json: String): SyncOp = parse(json).ops.single()

    // ---- reading the desktop's delete frames -----------------------------------------------------

    @Test
    fun `the device reads a desktop deleteContainer`() {
        val op = onlyOp(desktopDeleteContainer) as DeleteContainerOp

        assertEquals("BOX-101", op.containerId)
        assertEquals(1730000000000L, op.at)
    }

    @Test
    fun `the device reads a desktop deleteItem keyed on uuid`() {
        val op = onlyOp(desktopDeleteItemUuid) as DeleteItemOp

        assertEquals("BOX-101", op.containerId)
        assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", op.itemUuid)
        assertEquals(ItemKeyKind.Uuid, op.keyKind)
        assertEquals("3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83", op.keyValue)
        assertEquals("Cordless Drill 18V", op.itemName)
        assertEquals(1730000000001L, op.at)
    }

    @Test
    fun `the device reads a desktop deleteItem keyed on container plus barcode`() {
        val op = onlyOp(desktopDeleteItemContainerBarcode) as DeleteItemOp

        assertEquals(ItemKeyKind.ContainerBarcode, op.keyKind)
        assertEquals("BOX-101\u0000BBB", op.keyValue)
    }

    @Test
    fun `the device reads a desktop deleteItem keyed on name only`() {
        val op = onlyOp(desktopDeleteItemNameOnly) as DeleteItemOp

        assertEquals(ItemKeyKind.NameOnly, op.keyKind)
        assertEquals("BOX-101\u0000unlabelled spare", op.keyValue)
    }

    // ---- the NUL composite, which is the fiddly part ---------------------------------------------

    @Test
    fun `the NUL in a composite key survives the JSON round trip byte for byte`() {
        // The desktop wrote the NUL as the escape `\u0000`; the raw frame must therefore contain no
        // literal NUL character at all, or the frame would not be valid JSON text.
        assertTrue(
            "the desktop escapes the NUL rather than emitting it raw",
            desktopDeleteItemContainerBarcode.contains("""\u0000"""),
        )
        assertFalse(
            "a literal NUL in the frame would make it invalid JSON",
            desktopDeleteItemContainerBarcode.contains('\u0000'),
        )

        val op = onlyOp(desktopDeleteItemContainerBarcode) as DeleteItemOp

        // After parsing it must be a real NUL again, and the value must be byte-identical to what the
        // desktop's own reader produced for the same frame (captured as UTF-8 bytes 66,79,88,45,49,
        // 48,49,0,66,66,66 - i.e. "BOX-101", NUL, "BBB").
        assertEquals("BOX-101\u0000BBB", op.keyValue)
        assertEquals(11, op.keyValue.length)
        assertEquals('\u0000', op.keyValue[7])
        assertArrayEquals(
            byteArrayOf(66, 79, 88, 45, 49, 48, 49, 0, 66, 66, 66),
            op.keyValue.toByteArray(Charsets.UTF_8),
        )
    }

    // ---- the legacy path: no uuid, only a key -----------------------------------------------------

    @Test
    fun `the device accepts a deleteItem with an empty uuid but a populated key`() {
        // The one case a uuid cannot carry: a 1.0 row has no uuid, so the deletion travels with the
        // key the emitting device computed. The Android reader must not reject the empty uuid - it is
        // the key, not the uuid, that locates the row.
        val op = onlyOp(desktopDeleteItemLegacyNoUuid) as DeleteItemOp

        assertEquals("", op.itemUuid)
        assertEquals(ItemKeyKind.ContainerBarcode, op.keyKind)
        assertEquals("BOX-101\u0000LEGACY-9", op.keyValue)
    }

    // ---- case-insensitivity on read ---------------------------------------------------------------

    @Test
    fun `a keyKind written in a different casing still parses`() {
        // The desktop parses with `Enum.TryParse(..., ignoreCase: true)`; the Android reader must be
        // no stricter, or a peer that spelled the kind differently would have its deletion dropped.
        val op = onlyOp(lowercasedKeyKindFrame) as DeleteItemOp

        assertEquals(ItemKeyKind.ContainerBarcode, op.keyKind)
        assertEquals("BOX-101\u0000BBB", op.keyValue)
    }

    // ---- the writer direction ---------------------------------------------------------------------

    @Test
    fun `the device writes the exact bytes the desktop wrote for a NUL-bearing delete`() {
        // Round-trip through both directions: the Android writer must reproduce the desktop's frame
        // character for character, including the PascalCase keyKind and the escaped NUL. Anything
        // else means the desktop's reader would recompute a different key.
        val written = SyncJson.write(
            SyncMessage(
                type = SyncMessageType.Changes,
                seq = 9,
                src = "desktop-1",
                ops = listOf(
                    DeleteItemOp(
                        containerId = "BOX-101",
                        itemUuid = "",
                        keyKind = ItemKeyKind.ContainerBarcode,
                        keyValue = "BOX-101\u0000BBB",
                        itemName = "Tape",
                        at = 1730000000002L,
                    ),
                ),
            ),
        )

        assertEquals(desktopDeleteItemContainerBarcode, written)
    }

    @Test
    fun `the device writes the exact bytes the desktop wrote for a uuid-keyed delete`() {
        val written = SyncJson.write(
            SyncMessage(
                type = SyncMessageType.Changes,
                seq = 8,
                src = "desktop-1",
                ops = listOf(
                    DeleteItemOp(
                        containerId = "BOX-101",
                        itemUuid = "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
                        keyKind = ItemKeyKind.Uuid,
                        keyValue = "3f1c9e2a-8b47-4d16-9c05-7a2e6b1d4f83",
                        itemName = "Cordless Drill 18V",
                        at = 1730000000001L,
                    ),
                ),
            ),
        )

        assertEquals(desktopDeleteItemUuid, written)
    }

    @Test
    fun `the device writes the exact bytes the desktop wrote for a deleteContainer`() {
        val written = SyncJson.write(
            SyncMessage(
                type = SyncMessageType.Changes,
                seq = 7,
                src = "desktop-1",
                ops = listOf(DeleteContainerOp(containerId = "BOX-101", at = 1730000000000L)),
            ),
        )

        assertEquals(desktopDeleteContainer, written)
    }

    // ---- behaviour: the right row goes, the other stays -------------------------------------------

    @Test
    fun `a legacy row with no uuid is deleted by its merge key and the other row survives`() {
        // Mirrors the desktop's `A_legacy_row_with_no_uuid_is_deleted_by_its_merge_key`
        // (Scantron.Desktop.Tests/SyncSessionTests.cs). Two rows share the container; only the
        // barcode-keyed legacy row is deleted, and the deletion must not take the uuid-keyed row with
        // it - a key that matched too loosely would delete the wrong stock.
        val legacy = ContainerItem(
            containerId = "BOX-101",
            name = "Tape",
            barcode = "BBB",
            quantity = 1,
            uuid = "",
        )
        val drill = ContainerItem(
            containerId = "BOX-101",
            name = "Drill",
            barcode = "DDD",
            quantity = 1,
            uuid = "u2",
        )
        val base = InventoryDocument(
            containers = listOf(Container(id = "BOX-101")),
            items = listOf(legacy, drill),
        )

        val result = SyncApply.apply(
            base = base,
            local = base,
            ops = listOf(
                DeleteItemOp(
                    containerId = "BOX-101",
                    itemUuid = "",
                    keyKind = ItemKeyKind.ContainerBarcode,
                    keyValue = "BOX-101\u0000BBB",
                    itemName = "Tape",
                    at = 1,
                ),
            ),
        )

        val survivors = result.document.itemsOf("BOX-101")
        assertEquals("exactly one row must survive the delete", 1, survivors.size)
        assertEquals("the uuid-keyed row is the one that stays", "u2", survivors.single().uuid)
        assertEquals("Drill", survivors.single().name)
        assertTrue(
            "the legacy row must be gone from the displayed document, not merely from the base",
            result.document.itemsOf("BOX-101").none { it.name == "Tape" },
        )
    }
}
