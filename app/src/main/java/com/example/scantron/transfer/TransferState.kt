package com.example.scantron.transfer

/**
 * Progress of the single in-flight transfer.
 *
 * The hub handles one request at a time and the operator should never be able to start a second
 * one, so this is a single slot rather than a map keyed by direction. Exposing it as a
 * [kotlinx.coroutines.flow.StateFlow] is what lets the Compose screen disable its buttons and show
 * a spinner without any of the screen knowing how the transfer is driven.
 */
sealed interface TransferState {

    /** Nothing in flight. The buttons are enabled. */
    data object Idle : TransferState

    /** A transfer is running. The buttons are disabled. */
    data class Working(val operation: TransferOperation) : TransferState

    /** The last transfer finished successfully. [message] is safe to show verbatim. */
    data class Success(val message: String) : TransferState

    /** The last transfer failed. [message] is safe to show verbatim. */
    data class Failed(val message: String) : TransferState

    /** True whenever a transfer is in flight, regardless of which one. */
    val isBusy: Boolean get() = this is Working
}

/** Which of the three hub routes is being exercised, for the status line and spinner label. */
enum class TransferOperation(val label: String) {
    Health("Checking desktop"),
    Push("Sending to desktop"),
    Pull("Getting from desktop"),
}