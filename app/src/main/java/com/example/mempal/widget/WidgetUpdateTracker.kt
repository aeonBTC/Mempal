package com.example.mempal.widget

import kotlinx.coroutines.CompletableDeferred

/**
 * Lets a widget update run wait until all widget instances have finished their
 * network work. This keeps the worker (and its wake lock) alive long enough that
 * the process is not torn down between "broadcast sent" and "data fetched".
 *
 * It is not a data cache: it only tracks completion of the current run.
 */
object WidgetUpdateTracker {
    private val lock = Any()
    private var remaining = 0
    private var completion: CompletableDeferred<Unit>? = null

    fun begin(expected: Int): CompletableDeferred<Unit>? {
        if (expected <= 0) return null
        synchronized(lock) {
            remaining = expected
            return CompletableDeferred<Unit>().also { completion = it }
        }
    }

    fun completeOne() {
        synchronized(lock) {
            if (remaining <= 0) return
            remaining--
            if (remaining == 0) {
                completion?.complete(Unit)
                completion = null
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            completion?.complete(Unit)
            completion = null
            remaining = 0
        }
    }
}
