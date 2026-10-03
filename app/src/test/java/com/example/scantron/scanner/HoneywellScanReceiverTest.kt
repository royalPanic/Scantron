package com.example.scantron.scanner

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the Data Intent parsing and the [HoneywellScanReceiver] -> [ScanBus] hand-off.
 *
 * These tests simulate the exact broadcast the CK65 Data Collection Service emits; no CK65
 * hardware is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HoneywellScanReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun dataIntent(
        action: String = ScanEvent.ACTION,
        data: String? = "BIN-42",
        codeId: String? = "C",
        aimId: String? = "]C1",
        timestamp: String? = "2026-01-01T10:00:00Z",
        charset: String? = "UTF-8",
        dataBytes: ByteArray? = null,
    ): Intent = Intent(action).apply {
        data?.let { putExtra(ScanEvent.EXTRA_DATA, it) }
        codeId?.let { putExtra(ScanEvent.EXTRA_CODE_ID, it) }
        aimId?.let { putExtra(ScanEvent.EXTRA_AIM_ID, it) }
        timestamp?.let { putExtra(ScanEvent.EXTRA_TIMESTAMP, it) }
        charset?.let { putExtra(ScanEvent.EXTRA_CHARSET, it) }
        dataBytes?.let { putExtra(ScanEvent.EXTRA_DATA_BYTES, it) }
    }

    @Test
    fun `parses all Data Intent extras`() {
        val event = ScanEvent.fromIntent(
            dataIntent(dataBytes = byteArrayOf(0x01, 0x02, 0xAB.toByte())),
        )

        assertEquals("BIN-42", event.data)
        assertEquals("C", event.codeId)
        assertEquals("]C1", event.aimId)
        assertEquals("2026-01-01T10:00:00Z", event.timestamp)
        assertEquals("UTF-8", event.charset)
        assertEquals("0102ab", event.rawBytesHex)
        assertTrue(event.isValid)
    }

    @Test
    fun `missing extras degrade to null and empty payload`() {
        val event = ScanEvent.fromIntent(dataIntent(data = null, codeId = null, aimId = null))

        assertEquals("", event.data)
        assertNull(event.codeId)
        assertNull(event.aimId)
        assertFalse(event.isValid)
    }

    @Test
    fun `payload is trimmed`() {
        val event = ScanEvent.fromIntent(dataIntent(data = "  BOX-101\n"))
        assertEquals("BOX-101", event.data)
    }

    @Test
    fun `receiver forwards a valid scan to the bus`() =
        runTest(UnconfinedTestDispatcher()) {
            val received = mutableListOf<ScanEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ScanBus.events.collect { received += it }
            }
            runCurrent()

            HoneywellScanReceiver().onReceive(context, dataIntent(data = "BOX-101"))
            runCurrent()

            assertEquals(1, received.size)
            assertEquals("BOX-101", received.single().data)
            assertEquals("C", received.single().codeId)
            assertEquals("]C1", received.single().aimId)
        }

    @Test
    fun `receiver forwards a burst of scans in order`() =
        runTest(UnconfinedTestDispatcher()) {
            val received = mutableListOf<ScanEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ScanBus.events.collect { received += it }
            }
            runCurrent()

            val receiver = HoneywellScanReceiver()
            listOf("BOX-101", "BOX-102", "BOX-103").forEach { code ->
                receiver.onReceive(context, dataIntent(data = code))
            }
            runCurrent()

            assertEquals(listOf("BOX-101", "BOX-102", "BOX-103"), received.map { it.data })
        }

    @Test
    fun `receiver ignores broadcasts for a different action`() =
        runTest(UnconfinedTestDispatcher()) {
            val received = mutableListOf<ScanEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ScanBus.events.collect { received += it }
            }
            runCurrent()

            HoneywellScanReceiver().onReceive(
                context,
                dataIntent(action = "com.example.scantron.action.OTHER"),
            )
            runCurrent()

            assertTrue(received.isEmpty())
        }

    @Test
    fun `receiver drops scans with an empty payload`() =
        runTest(UnconfinedTestDispatcher()) {
            val received = mutableListOf<ScanEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ScanBus.events.collect { received += it }
            }
            runCurrent()

            HoneywellScanReceiver().onReceive(context, dataIntent(data = "   "))
            runCurrent()

            assertTrue(received.isEmpty())
        }
}
