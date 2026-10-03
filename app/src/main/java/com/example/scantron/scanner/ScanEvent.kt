package com.example.scantron.scanner

import android.content.Intent

/**
 * A single decoded barcode delivered by the Honeywell Data Collection Service (DCS)
 * through a "Data Intent" broadcast.
 *
 * The DCS populates a fixed set of extras on the broadcast intent. The default keys are
 * listed below; they can be renamed on the device under
 * `Settings > Honeywell Settings > Scanning > Internal Scanner > Default Profile >
 * Data Processing Settings > Data Intent > Extra Key`.
 *
 * When a custom "Extra Key" is configured on the device the DCS still emits the default
 * keys, so [fromIntent] keeps working either way.
 */
data class ScanEvent(
    /** Decoded barcode payload. */
    val data: String,
    /** Honeywell symbology identifier, e.g. `C` for Code 128. */
    val codeId: String? = null,
    /** AIM symbology identifier, e.g. `]C1`. */
    val aimId: String? = null,
    /** Scan timestamp reported by the DCS. */
    val timestamp: String? = null,
    /** Character set used to encode the payload. */
    val charset: String? = null,
    /**
     * Raw decoded bytes rendered as lowercase hex. Kept as hex rather than [ByteArray] so
     * the value participates in `equals`/`hashCode` as a normal data-class field.
     */
    val rawBytesHex: String? = null,
) {
    /** A scan is only useful once it actually carries a payload. */
    val isValid: Boolean get() = data.isNotBlank()

    companion object {
        const val ACTION = "com.example.scantron.action.BARCODE_SCAN"

        const val EXTRA_VERSION = "version"
        const val EXTRA_DATA = "data"
        const val EXTRA_DATA_BYTES = "dataBytes"
        const val EXTRA_CHARSET = "charset"
        const val EXTRA_CODE_ID = "codeId"
        const val EXTRA_AIM_ID = "aimId"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_SCANNER = "scanner"

        fun fromIntent(intent: Intent): ScanEvent = ScanEvent(
            data = intent.getStringExtra(EXTRA_DATA)?.trim().orEmpty(),
            codeId = intent.getStringExtra(EXTRA_CODE_ID),
            aimId = intent.getStringExtra(EXTRA_AIM_ID),
            timestamp = intent.getStringExtra(EXTRA_TIMESTAMP),
            charset = intent.getStringExtra(EXTRA_CHARSET),
            rawBytesHex = intent.getByteArrayExtra(EXTRA_DATA_BYTES)?.toHexString(),
        )

        private fun ByteArray.toHexString(): String =
            joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
