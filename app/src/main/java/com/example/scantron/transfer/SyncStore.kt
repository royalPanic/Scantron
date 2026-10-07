package com.example.scantron.transfer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.example.scantron.data.ExportImportManager
import com.example.scantron.data.InventoryDocument
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Persists the shared base document, the Lamport clock and the pairing, so a sync survives process
 * death.
 *
 * Two storage mechanisms, chosen for what each holds:
 *
 *  * **The base document** is a file, because it is a whole inventory and it must be written through
 *    exactly the same JSON path [ExportImportManager] uses. Reading it back with the same reader is
 *    what guarantees a base written by live sync is a document a USB import would also accept - the
 *    two representations cannot drift if there is only one.
 *  * **The clock and the pairing** are small scalars, and `SharedPreferences` is the right home: they
 *    must be readable before any file I/O and updatable without a full rewrite.
 *
 * The base is written to a temp file and moved into place. An interrupted write must never leave a
 * half-written base behind, because a truncated base would parse as a document holding *less* than
 * the two devices agreed on - and every row missing from it would then look like a local edit and be
 * re-sent. A rename is atomic, so the file is either the old base or the new one.
 */
class SyncStore(
    context: Context,
    /** Where the base file lives; injectable so tests can use a scratch directory. */
    private val baseDirectory: File = File(context.applicationContext.filesDir, DIRECTORY),
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted base, or null when there has never been a successful sync. */
    fun readBase(): InventoryDocument? {
        val file = baseFile()
        if (!file.exists()) return null

        return try {
            ExportImportManager.parseDocument(file.readText())
        } catch (e: Exception) {
            // A base that cannot be read is treated as "no base" rather than as a fatal error. The
            // cost is one full snapshot on the next sync; the alternative - refusing to sync ever
            // again - is the failure that leaves the handheld permanently stale.
            Log.w(TAG, "Stored sync base is unreadable; will re-snapshot", e)
            null
        }
    }

    /** Writes [document] as the new base, atomically. */
    fun writeBase(document: InventoryDocument) {
        val target = baseFile()
        val temp = File(target.parentFile, "${target.name}.tmp")

        try {
            target.parentFile?.mkdirs()
            temp.writeText(ExportImportManager.buildDocument(document.containers, document.items))
            if (!temp.renameTo(target)) {
                // A rename onto an existing file can fail on some volumes; a copy-then-rename keeps
                // the atomicity guarantee where it matters and only widens the window here.
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not persist the sync base", e)
            temp.delete()
        }
    }

    /** Removes the base, forcing the next sync to exchange full snapshots. */
    fun clearBase() {
        baseFile().delete()
    }

    fun baseExists(): Boolean = baseFile().exists()

    /**
     * How long ago the base was written, or [Long.MAX_VALUE] when there is no base.
     *
     * Used to choose between an incremental `changes` exchange and a full `snapshot`: a base older
     * than the tombstone horizon has lost the tombstones that would have carried its deletions, so
     * an incremental replay against it cannot be trusted and the whole document must go instead.
     */
    fun baseAgeMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        val file = baseFile()
        if (!file.exists()) return Long.MAX_VALUE
        return nowMillis - file.lastModified()
    }

    /**
     * The device id announced in `hello`.
     *
     * Minted once and reused for the life of the install so the desktop can recognise a device it has
     * paired with before. Deliberately not derived from anything hardware-specific: the id must not be
     * guessable from an operator-visible value, and a factory-reset handheld getting a fresh id is the
     * correct outcome.
     */
    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val minted = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, minted) }
        return minted
    }

    /**
     * Advances and returns the Lamport clock.
     *
     * A Lamport clock rather than the wall clock, because `updatedAt` is a *change signal* and the two
     * devices' wall clocks are not comparable - a CK65 an hour behind would otherwise have every edit
     * look older than the desktop's base. The value is persisted so a restart does not reissue a
     * sequence number the desktop has already acknowledged.
     */
    @Synchronized
    fun nextClock(): Long {
        val next = prefs.getLong(KEY_CLOCK, 0L) + 1
        prefs.edit { putLong(KEY_CLOCK, next) }
        return next
    }

    /**
     * Raises the clock to at least [observed] and returns the value for the *next* frame.
     *
     * Called when a peer's sequence number is seen, so this device cannot reissue one the peer has
     * already used. Returns the advanced clock rather than the observed value because the caller is
     * about to send its own frame, and handing back a number equal to the peer's would defeat the
     * purpose of the call.
     */
    @Synchronized
    fun observeClock(observed: Long): Long {
        val current = prefs.getLong(KEY_CLOCK, 0L)
        val next = maxOf(current, observed) + 1
        prefs.edit { putLong(KEY_CLOCK, next) }
        return next
    }

    fun lastClock(): Long = prefs.getLong(KEY_CLOCK, 0L)

    /** The desktop this device last paired with, for auto-reconnect. Null when never paired. */
    fun lastPairedHost(): String? =
        prefs.getString(KEY_LAST_HOST, null)?.takeIf { it.isNotBlank() }

    fun rememberPairedHost(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        prefs.edit { putString(KEY_LAST_HOST, trimmed) }
    }

    /**
     * True when a pairing has happened at all.
     *
     * Separate from [lastPairedHost] because a base with no remembered host (or vice versa) means the
     * two halves of the pairing have drifted, and the safe answer is to re-pair rather than to guess
     * which half is right.
     */
    fun isPaired(): Boolean = lastPairedHost() != null

    fun forgetPairing() {
        prefs.edit {
            remove(KEY_LAST_HOST)
            remove(KEY_CLOCK)
        }
        clearBase()
    }

    /**
     * Drops scratch files older than [olderThanMillis].
     *
     * Only abandoned temp files can accumulate here, so this is housekeeping rather than a
     * correctness requirement - but an unbounded directory on a handheld is a slow disk failure, and
     * the prune is cheap.
     */
    fun pruneScratchFiles(
        olderThanMillis: Long = TOMBSTONE_HORIZON_MS,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val cutoff = nowMillis - olderThanMillis
        baseDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    private fun baseFile(): File = File(baseDirectory, BASE_FILE_NAME)

    companion object {
        private const val TAG = "SyncStore"

        /** Directory under `files/`, so the base is not exposed to the file-transfer UI. */
        const val DIRECTORY = "sync"

        /** Named `.json` so it is obvious the file is the export format and not a private shape. */
        const val BASE_FILE_NAME = "base.json"

        private const val PREFS_NAME = "scantron_sync"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CLOCK = "lamport_clock"
        private const val KEY_LAST_HOST = "last_paired_host"

        /**
         * How long a tombstone is kept.
         *
         * A peer that has been away longer than this cannot be sent an incremental replay with any
         * confidence - its base may predate the horizon - so it is sent a full snapshot instead, which
         * carries every deletion implicitly because a snapshot states what the document *is*.
         */
        const val TOMBSTONE_HORIZON_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
