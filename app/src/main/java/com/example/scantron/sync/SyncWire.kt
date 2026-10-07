package com.example.scantron.sync

import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.data.ItemKeyKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Discriminator for [SyncOp], spelled the same way on the wire as the desktop's enum. */
enum class SyncOpKind {
    /** Whole-document replacement. The first-sync / no-usable-base case. */
    Snapshot,

    /** Container metadata, without its rows. */
    UpsertContainer,

    /** One item row, whole. */
    UpsertItem,

    /** A container that no longer exists. */
    DeleteContainer,

    /** An item row that no longer exists. */
    DeleteItem,

    /** An operator's decision on one conflicted field. */
    Resolve,
}

/**
 * One unit of change on the live-sync wire.
 *
 * Granularity is deliberately *whole rows*, not field diffs. The field-level intelligence lives in
 * [InventoryMerger], which is where it is tested; duplicating it into the transport would be a
 * second place for the two devices to disagree. Every op that is not a [SnapshotOp] is expressed
 * *relative to the shared base*, which is what makes an op idempotent: replaying it rebuilds the
 * same "peer" view of the row, and the base advances only once.
 */
sealed interface SyncOp {
    val kind: SyncOpKind
}

/**
 * Replaces the peer's entire view. Sent only when no shared base exists.
 *
 * [json] is kept alongside the parsed [document] on purpose: the receiver has to hand the *bytes* to
 * the same reader and validator a USB import uses, so a live sync and a file transfer remain
 * interchangeable by construction rather than by convention.
 */
data class SnapshotOp(val json: String, val document: com.example.scantron.data.InventoryDocument) : SyncOp {
    override val kind: SyncOpKind get() = SyncOpKind.Snapshot
}

/**
 * Container metadata changed. No rows travel with it: rows are their own ops, so a deletion can be
 * expressed as a deletion rather than as an omission.
 *
 * Omission cannot carry a delete. [InventoryMerger] treats a row missing from one side as "no
 * opinion" and carries it forward, so a container upsert that merely listed fewer rows would
 * resurrect them on the next sync. Only an explicit [DeleteItemOp] removes a row.
 */
data class UpsertContainerOp(val container: Container) : SyncOp {
    override val kind: SyncOpKind get() = SyncOpKind.UpsertContainer
}

/** One item row, whole, as it now stands. */
data class UpsertItemOp(val containerId: String, val item: ContainerItem) : SyncOp {
    override val kind: SyncOpKind get() = SyncOpKind.UpsertItem
}

/** A container that has been removed. Its rows go with it; no per-row tombstones are emitted. */
data class DeleteContainerOp(val containerId: String, val at: Long) : SyncOp {
    override val kind: SyncOpKind get() = SyncOpKind.DeleteContainer
}

/**
 * An item row that has been removed.
 *
 * Carries the merge *key* as well as the uuid, because a legacy 1.0 row has no uuid and the key is
 * the only identity both devices can agree on. The key is what the emitting side's [itemKeyOf]
 * produced, and because both devices share the same base the receiver recomputes the identical key
 * against its own copy. [itemName] is not used to locate anything; it exists so a conflict or a log
 * line can name the row to an operator instead of showing a uuid.
 */
data class DeleteItemOp(
    val containerId: String,
    val itemUuid: String,
    val keyKind: ItemKeyKind,
    val keyValue: String,
    val itemName: String,
    val at: Long,
) : SyncOp {
    override val kind: SyncOpKind get() = SyncOpKind.DeleteItem
}

/** An operator's decision on one conflicted field. */
data class ResolveOp(
    val containerId: String,
    val itemUuid: String,
    val field: String,
    val value: String?,
    val at: Long,
) : SyncOp {
    override val kind: SyncOpKind get() = SyncOpKind.Resolve
}

/** Message kinds on the live-sync wire. The set is deliberately closed and small. */
enum class SyncMessageType {
    /** Device to desktop: who I am. Always the first frame. */
    Hello,

    /** Device to desktop: the pairing code shown on the desktop. */
    Pair,

    /** Desktop to device: accepted; carries the session id. */
    Paired,

    /** Whole-document replacement, for a first sync. */
    Snapshot,

    /** A batch of row changes, all relative to the shared base. */
    Changes,

    /** Durable application of ops up to a sequence number. */
    Ack,

    /** An open, unresolved same-field conflict. */
    Conflict,

    /** An operator decision on a conflicted field. */
    Resolve,

    /** Keepalive. */
    Ping,

    /** Keepalive reply. */
    Pong,

    /** Graceful, reasoned shutdown. */
    Bye,
}

data class HelloInfo(val deviceId: String, val name: String, val protocolVersion: Int)

data class PairInfo(val code: String)

data class PairedInfo(val deviceId: String, val desktopName: String, val sessionId: String)

/** One unresolved conflict, as reported by either device. */
data class ConflictInfo(
    val conflictId: String,
    val containerId: String,
    val itemUuid: String,
    val itemName: String,
    val field: String,
    val baseValue: String?,
    val localValue: String?,
    val remoteValue: String?,
)

/**
 * One frame on the live-sync wire.
 *
 * [seq] is this device's monotonic counter, for *ordering and acknowledgement on a single
 * connection* rather than for conflict resolution - the base document is what decides who changed
 * what. That is why an edit made while offline reconciles correctly on reconnect without any
 * cross-session clock agreement.
 */
data class SyncMessage(
    val type: SyncMessageType,
    val seq: Long = 0,
    val src: String = "",
    val hello: HelloInfo? = null,
    val pair: PairInfo? = null,
    val paired: PairedInfo? = null,
    val ops: List<SyncOp> = emptyList(),
    val conflict: ConflictInfo? = null,
    val ackSeq: Long = 0,
    val reason: String? = null,
)

/** Raised when a frame is readable JSON but is not a usable live-sync frame. */
class SyncWireException(message: String) : Exception(message)

/**
 * JSON for live-sync frames, mirroring the desktop's `Sync/SyncWire.cs` field for field.
 *
 * Hand-written over `org.json` rather than data-class reflection, for the same reason the desktop
 * hand-writes it over `Utf8JsonWriter`: the discriminator is the contract, and a discriminator a
 * serialization library infers is one a rename can silently change. Field names are written out
 * literally, once, here.
 *
 * Rows and containers travel in the *same shape* the export document uses, so a row on the live
 * wire and the same row in a file are identical in meaning - that is what keeps a live sync and a
 * USB transfer interchangeable.
 *
 * Nothing here throws for bad input. A malformed frame on a socket is an ordinary event - a version
 * mismatch or a truncated read - and it has to become a sentence on the status line, not an
 * exception that takes the session down.
 */
object SyncJson {

    /** Protocol version this build speaks. A mismatch is refused, not negotiated. */
    const val PROTOCOL_VERSION: Int = 1

    /** Outcome of parsing one frame. */
    sealed interface ReadResult {
        data class Parsed(val message: SyncMessage) : ReadResult
        data class Invalid(val reason: String) : ReadResult
    }

    fun write(message: SyncMessage): String {
        val root = JSONObject()
            .put("type", wireName(message.type))
            .put("seq", message.seq)

        if (message.src.isNotEmpty()) {
            root.put("src", message.src)
        }

        message.hello?.let { hello ->
            root.put(
                "hello",
                JSONObject()
                    .put("deviceId", hello.deviceId)
                    .put("name", hello.name)
                    .put("protocolVersion", hello.protocolVersion),
            )
        }

        message.pair?.let { pair -> root.put("pair", JSONObject().put("code", pair.code)) }

        message.paired?.let { paired ->
            root.put(
                "paired",
                JSONObject()
                    .put("deviceId", paired.deviceId)
                    .put("desktopName", paired.desktopName)
                    .put("sessionId", paired.sessionId),
            )
        }

        if (message.ops.isNotEmpty()) {
            root.put("ops", JSONArray().apply { message.ops.forEach { put(writeOp(it)) } })
        }

        message.conflict?.let { conflict ->
            root.put(
                "conflict",
                JSONObject()
                    .put("conflictId", conflict.conflictId)
                    .put("containerId", conflict.containerId)
                    .put("itemUuid", conflict.itemUuid)
                    .put("itemName", conflict.itemName)
                    .put("field", conflict.field)
                    .putNullable("baseValue", conflict.baseValue)
                    .putNullable("localValue", conflict.localValue)
                    .putNullable("remoteValue", conflict.remoteValue),
            )
        }

        if (message.ackSeq > 0) {
            root.put("ackSeq", message.ackSeq)
        }

        message.reason?.takeIf { it.isNotEmpty() }?.let { root.put("reason", it) }

        return root.toString()
    }

    fun read(json: String): ReadResult {
        if (json.isBlank()) {
            return ReadResult.Invalid("The desktop sent an empty frame.")
        }

        return try {
            val root = JSONObject(json)
            val typeName = root.optString("type", "")
            val type = typeFromWire(typeName)
                ?: return ReadResult.Invalid(
                    "The desktop sent a frame this build does not understand. " +
                        "Both devices must be running the same Scantron version.",
                )

            val ops = readOps(root)
            if (ops is OpsResult.Invalid) {
                return ReadResult.Invalid(ops.reason)
            }

            ReadResult.Parsed(
                SyncMessage(
                    type = type,
                    seq = root.optLong("seq", 0),
                    src = root.optString("src", ""),
                    hello = root.optJSONObject("hello")?.let {
                        HelloInfo(
                            deviceId = it.optString("deviceId", ""),
                            name = it.optString("name", ""),
                            protocolVersion = it.optInt("protocolVersion", 0),
                        )
                    },
                    pair = root.optJSONObject("pair")?.let { PairInfo(it.optString("code", "")) },
                    paired = root.optJSONObject("paired")?.let {
                        PairedInfo(
                            deviceId = it.optString("deviceId", ""),
                            desktopName = it.optString("desktopName", ""),
                            sessionId = it.optString("sessionId", ""),
                        )
                    },
                    ops = (ops as OpsResult.Parsed).ops,
                    conflict = root.optJSONObject("conflict")?.let {
                        ConflictInfo(
                            conflictId = it.optString("conflictId", ""),
                            containerId = it.optString("containerId", ""),
                            itemUuid = it.optString("itemUuid", ""),
                            itemName = it.optString("itemName", ""),
                            field = it.optString("field", ""),
                            baseValue = it.nullableString("baseValue"),
                            localValue = it.nullableString("localValue"),
                            remoteValue = it.nullableString("remoteValue"),
                        )
                    },
                    ackSeq = root.optLong("ackSeq", 0),
                    reason = root.nullableString("reason"),
                ),
            )
        } catch (e: JSONException) {
            ReadResult.Invalid("The desktop sent a frame that is not valid JSON.")
        }
    }

    /**
     * The stable wire name of a message type.
     *
     * Written out explicitly rather than lowercasing the enum's own name, so a future rename in
     * Kotlin shows up as a compile-time exhaustive-`when` failure instead of silently changing the
     * contract.
     */
    fun wireName(type: SyncMessageType): String = when (type) {
        SyncMessageType.Hello -> "hello"
        SyncMessageType.Pair -> "pair"
        SyncMessageType.Paired -> "paired"
        SyncMessageType.Snapshot -> "snapshot"
        SyncMessageType.Changes -> "changes"
        SyncMessageType.Ack -> "ack"
        SyncMessageType.Conflict -> "conflict"
        SyncMessageType.Resolve -> "resolve"
        SyncMessageType.Ping -> "ping"
        SyncMessageType.Pong -> "pong"
        SyncMessageType.Bye -> "bye"
    }

    /** The wire name of an op kind, matching the desktop's `OpName`. */
    fun wireName(kind: SyncOpKind): String = when (kind) {
        SyncOpKind.Snapshot -> "snapshot"
        SyncOpKind.UpsertContainer -> "upsertContainer"
        SyncOpKind.UpsertItem -> "upsertItem"
        SyncOpKind.DeleteContainer -> "deleteContainer"
        SyncOpKind.DeleteItem -> "deleteItem"
        SyncOpKind.Resolve -> "resolve"
    }

    /** The wire name of an item key kind, matching the desktop's `ItemKeyKind.ToString()`. */
    fun wireName(kind: ItemKeyKind): String = when (kind) {
        ItemKeyKind.Uuid -> "Uuid"
        ItemKeyKind.ContainerBarcode -> "ContainerBarcode"
        ItemKeyKind.NameOnly -> "NameOnly"
    }

    private fun typeFromWire(name: String): SyncMessageType? = when (name.lowercase()) {
        "hello" -> SyncMessageType.Hello
        "pair" -> SyncMessageType.Pair
        "paired" -> SyncMessageType.Paired
        "snapshot" -> SyncMessageType.Snapshot
        "changes" -> SyncMessageType.Changes
        "ack" -> SyncMessageType.Ack
        "conflict" -> SyncMessageType.Conflict
        "resolve" -> SyncMessageType.Resolve
        "ping" -> SyncMessageType.Ping
        "pong" -> SyncMessageType.Pong
        "bye" -> SyncMessageType.Bye
        else -> null
    }

    private fun kindFromWire(name: String): ItemKeyKind? = when (name.lowercase()) {
        "uuid" -> ItemKeyKind.Uuid
        "containerbarcode" -> ItemKeyKind.ContainerBarcode
        "nameonly" -> ItemKeyKind.NameOnly
        else -> null
    }

    private fun writeOp(op: SyncOp): JSONObject {
        val json = JSONObject().put("op", wireName(op.kind))

        when (op) {
            is SnapshotOp -> json.put("document", JSONObject(op.json))

            is UpsertContainerOp -> json.put(
                "container",
                JSONObject()
                    .put("id", op.container.id)
                    .put("name", op.container.name)
                    .put("location", op.container.location)
                    .put("notes", op.container.notes)
                    .put("updatedAt", op.container.updatedAt),
            )

            is UpsertItemOp -> {
                json.put("containerId", op.containerId)
                json.put("item", writeItem(op.item))
            }

            is DeleteContainerOp -> {
                json.put("containerId", op.containerId)
                json.put("at", op.at)
            }

            is DeleteItemOp -> {
                json.put("containerId", op.containerId)
                json.put("itemUuid", op.itemUuid)
                json.put("keyKind", wireName(op.keyKind))
                json.put("keyValue", op.keyValue)
                json.put("itemName", op.itemName)
                json.put("at", op.at)
            }

            is ResolveOp -> {
                json.put("containerId", op.containerId)
                json.put("itemUuid", op.itemUuid)
                json.put("field", op.field)
                json.putNullable("value", op.value)
                json.put("at", op.at)
            }
        }

        return json
    }

    private fun writeItem(item: ContainerItem): JSONObject = JSONObject()
        .put("name", item.name)
        .put("uuid", item.uuid)
        .put("barcode", item.barcode)
        .put("quantity", item.quantity)
        .put("category", item.category)
        .put("notes", item.notes)
        .put("updatedAt", item.updatedAt)

    private sealed interface OpsResult {
        data class Parsed(val ops: List<SyncOp>) : OpsResult
        data class Invalid(val reason: String) : OpsResult
    }

    private fun readOps(root: JSONObject): OpsResult {
        val array = root.optJSONArray("ops") ?: return OpsResult.Parsed(emptyList())
        val ops = mutableListOf<SyncOp>()

        for (i in 0 until array.length()) {
            val element = array.optJSONObject(i)
                ?: return OpsResult.Invalid("The desktop sent a change that is not an object.")

            when (val parsed = readOp(element)) {
                is OpResult.Parsed -> ops += parsed.op
                // A single unreadable op poisons the batch: applying the rest would leave the
                // desktop's view half-reconstructed, which is worse than refusing the whole frame.
                is OpResult.Invalid -> return OpsResult.Invalid(parsed.reason)
            }
        }

        return OpsResult.Parsed(ops)
    }

    private sealed interface OpResult {
        data class Parsed(val op: SyncOp) : OpResult
        data class Invalid(val reason: String) : OpResult
    }

    private fun readOp(element: JSONObject): OpResult {
        val name = element.optString("op", "")

        when (name) {
            "snapshot" -> {
                val document = element.optJSONObject("document")
                    ?: return OpResult.Invalid("A snapshot arrived without a document.")

                val json = document.toString()
                return try {
                    OpResult.Parsed(SnapshotOp(json, com.example.scantron.data.ExportImportManager.parseDocument(json)))
                } catch (e: Exception) {
                    // The desktop's own validator message, so the two sides explain a rejection the
                    // same way instead of the handheld inventing its own wording.
                    OpResult.Invalid("The desktop sent a document this build cannot read: ${e.message}")
                }
            }

            "upsertContainer" -> {
                val container = element.optJSONObject("container")
                    ?: return OpResult.Invalid("A container change arrived without its \"container\" object.")

                val id = container.optString("id", "").trim()
                if (id.isEmpty()) {
                    return OpResult.Invalid("A container arrived without a tag.")
                }

                return OpResult.Parsed(
                    UpsertContainerOp(
                        Container(
                            id = id,
                            name = container.optString("name", ""),
                            location = container.optString("location", ""),
                            notes = container.optString("notes", ""),
                            updatedAt = container.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    ),
                )
            }

            "upsertItem" -> {
                val item = element.optJSONObject("item")
                    ?: return OpResult.Invalid("A row change arrived without its \"item\" object.")

                val containerId = element.optString("containerId", "").trim()
                if (containerId.isEmpty()) {
                    return OpResult.Invalid("A row arrived without a container tag.")
                }

                val rowName = item.optString("name", "").trim()
                if (rowName.isEmpty()) {
                    // The validator would reject this on the way into a document anyway; catching it
                    // here names the actual row instead of the whole document.
                    return OpResult.Invalid("A row in \"$containerId\" arrived without a name.")
                }

                return OpResult.Parsed(
                    UpsertItemOp(
                        containerId = containerId,
                        item = ContainerItem(
                            id = 0,
                            containerId = containerId,
                            name = rowName,
                            barcode = item.optString("barcode", ""),
                            quantity = item.optInt("quantity", 1),
                            category = item.optString("category", ""),
                            notes = item.optString("notes", ""),
                            uuid = item.optString("uuid", "").trim(),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    ),
                )
            }

            "deleteContainer" -> return OpResult.Parsed(
                DeleteContainerOp(
                    containerId = element.optString("containerId", "").trim(),
                    at = element.optLong("at", 0),
                ),
            )

            "deleteItem" -> {
                val keyKind = kindFromWire(element.optString("keyKind", ""))
                    ?: return OpResult.Invalid("A row deletion arrived with an unrecognised identity kind.")

                return OpResult.Parsed(
                    DeleteItemOp(
                        containerId = element.optString("containerId", "").trim(),
                        itemUuid = element.optString("itemUuid", "").trim(),
                        keyKind = keyKind,
                        keyValue = element.optString("keyValue", ""),
                        itemName = element.optString("itemName", ""),
                        at = element.optLong("at", 0),
                    ),
                )
            }

            "resolve" -> return OpResult.Parsed(
                ResolveOp(
                    containerId = element.optString("containerId", "").trim(),
                    itemUuid = element.optString("itemUuid", "").trim(),
                    field = element.optString("field", ""),
                    value = element.nullableString("value"),
                    at = element.optLong("at", 0),
                ),
            )

            else -> return OpResult.Invalid(
                "The desktop sent a change this build does not understand (\"$name\"). " +
                    "Both devices must be running the same Scantron version.",
            )
        }
    }
}

/**
 * Writes a value that the desktop expects as an explicit JSON null rather than an absent key.
 *
 * `org.json` has no direct "put null" - `put(name, null as Any?)` *removes* the key, while
 * `JSONObject.NULL` writes a real null. Since `optString` on a real null yields the literal text
 * "null" on the desktop's reader, the distinction is not cosmetic and this helper is not
 * decoration.
 */
private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
    if (value == null) put(name, JSONObject.NULL) else put(name, value)

/** Reads a string that the peer may have sent as an explicit JSON null, or omitted entirely. */
private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name, null)
