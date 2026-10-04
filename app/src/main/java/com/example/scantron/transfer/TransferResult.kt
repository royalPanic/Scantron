package com.example.scantron.transfer

/**
 * Outcome of one call to the desktop hub.
 *
 * The [message] is written for an operator standing in a warehouse, not for a developer. On
 * failure it is either the desktop's own plain-text reason (the hub returns a 4xx/5xx with a
 * human-readable body, and that body is almost always the most useful thing available) or a
 * plain description of what went wrong on this side. Exception class names are deliberately
 * never surfaced - "java.net.SocketTimeoutException" tells an operator nothing they can act on.
 */
sealed interface TransferResult {

    /** A human-readable outcome. Safe to show verbatim in the UI. */
    val message: String

    /**
     * The hub answered 2xx.
     *
     * [body] is the raw response body: for `/pull` that is the unmodified Scantron export
     * document, which must be handed to `importFromJsonString` untouched.
     */
    data class Success(
        val statusCode: Int,
        val body: String,
        override val message: String,
    ) : TransferResult

    /** No usable answer from the desktop. [message] says why, in plain words. */
    data class Failure(
        override val message: String,
        /** HTTP status when the hub answered at all; `-1` for connect/timeout/read errors. */
        val statusCode: Int = -1,
    ) : TransferResult
}