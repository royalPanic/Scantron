package com.example.scantron.scanner

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide hand-off point between [HoneywellScanReceiver] and the Compose layer.
 *
 * The receiver runs on the main thread inside a `BroadcastReceiver` callback, so it must
 * not block. [emit] therefore uses `tryEmit` against a buffered flow; if a burst of scans
 * ever outruns the collector the oldest event is dropped rather than blocking the
 * broadcast dispatch queue.
 */
object ScanBus {

    private val _events = MutableSharedFlow<ScanEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<ScanEvent> = _events.asSharedFlow()

    fun emit(event: ScanEvent) {
        _events.tryEmit(event)
    }
}
