package com.example.scantron.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Receives decoded barcode data from the Honeywell Data Collection Service (DCS) running on
 * the CK65. No Honeywell SDK / IntentAPI dependency is used - the DCS simply broadcasts a
 * [ScanEvent.ACTION] intent carrying the decoded payload as extras.
 *
 * Device setup (once, on the CK65):
 * `Settings > Honeywell Settings > Scanning > Internal Scanner > Default Profile >
 * Data Processing Settings > Data Intent`
 *  - enable **Data Intent**
 *  - set the **Data Intent Action** to [ScanEvent.ACTION]
 *
 * The receiver is registered dynamically (see `MainActivity`) rather than declared in the
 * manifest, because implicit broadcast restrictions on API 26+ apply to manifest entries but
 * not to context-registered receivers - the DCS broadcast is a normal system broadcast.
 */
class HoneywellScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScanEvent.ACTION) {
            Log.d(TAG, "Ignoring broadcast for unexpected action: ${intent.action}")
            return
        }

        val event = ScanEvent.fromIntent(intent)
        if (!event.isValid) {
            Log.w(TAG, "Data Intent broadcast carried no barcode payload; ignoring.")
            return
        }

        Log.i(
            TAG,
            "Scan received: '${event.data}' codeId=${event.codeId} aimId=${event.aimId} " +
                "timestamp=${event.timestamp} charset=${event.charset}",
        )
        ScanBus.emit(event)
    }

    companion object {
        private const val TAG = "HoneywellScanReceiver"

        /**
         * The [IntentFilter] required to observe DCS Data Intent broadcasts. The
         * `android.intent.category.DEFAULT` category must be present or the DCS broadcast
         * will not match.
         */
        fun intentFilter(): IntentFilter = IntentFilter(ScanEvent.ACTION).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }
}
