package com.example.scantron.ui.transfer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the last desktop the operator successfully reached.
 *
 * `SharedPreferences` rather than in-memory state because the failure this defends against is
 * specific and ugly: Android kills the app process to reclaim memory whenever it feels like it,
 * and in a warehouse that routinely happens between opening a container in the morning and
 * reaching for the transfer screen in the afternoon. Without this the operator retypes an IP
 * every time, and on a CK65 keyboard that is enough friction for the feature to go unused.
 *
 * Only a host that actually answered `/health` is stored. Saving whatever was typed would mean
 * the field is re-populated with the last *mistake* after a bad morning.
 *
 * The port is not persisted: it is fixed by the wire contract, so there is nothing for the
 * operator to have got wrong.
 */
class TransferHostStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The last address that responded, or empty string if there has never been one. */
    fun lastHost(): String = prefs.getString(KEY_LAST_HOST, "").orEmpty()

    /** Remember [host] as the working desktop. Blank values are ignored. */
    fun remember(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        prefs.edit { putString(KEY_LAST_HOST, trimmed) }
    }

    private companion object {
        const val PREFS_NAME = "scantron_transfer"
        const val KEY_LAST_HOST = "last_host"
    }
}