package com.hm.video.data.api

import okhttp3.Call
import java.io.IOException

/**
 * Binds cancellable page work to the user action that started it.
 *
 * A thread's start order is not the user's order, so cancellation is keyed by generation instead
 * of "whatever registered last":
 *
 *  - [newRequest] opens a new generation and cancels the call that is in flight.
 *  - Work started earlier that has not reached the network yet carries an older ticket and is
 *    refused, so a slow thread can never cancel a newer request.
 *  - [cancel] invalidates every outstanding ticket (destroyed screen), so a late task cannot
 *    start a request after the screen is gone.
 *  - One ticket may issue several sequential calls (a play page followed by its player service).
 *  - Cache refreshes run without a ticket, so they can never cancel a foreground request.
 */
class PageCallScope {

    /** One user action; all sequential calls of that action share it. */
    class Ticket internal constructor(internal val owner: PageCallScope, internal val generation: Int) {

        /** Registers [call] as the scope's active call, unless a newer action replaced this one. */
        internal fun register(call: Call): Boolean = owner.register(this, call)

        internal fun release(call: Call) = owner.release(this, call)

        /** Refuses [call] immediately when this ticket belongs to a superseded action. */
        internal fun claim(call: Call) {
            if (!register(call)) {
                call.cancel()
                throw IOException("请求已被更新的操作取代")
            }
        }

        /** Prevents a completed but superseded operation from publishing stale cache data. */
        internal fun ensureCurrent() {
            if (!owner.isCurrent(this)) throw IOException("请求已被更新的操作取代")
        }
    }

    private var generation = 0
    private var active: Call? = null

    /** Starts a new user action: invalidates older tickets and cancels the current call. */
    @Synchronized
    fun newRequest(): Ticket {
        generation++
        cancelActive()
        return Ticket(this, generation)
    }

    /** Invalidates every outstanding ticket and cancels the current call (screen destroyed). */
    @Synchronized
    fun cancel() {
        generation++
        cancelActive()
    }

    private fun cancelActive() {
        val call = active
        active = null
        call?.cancel()
    }

    @Synchronized
    private fun isCurrent(ticket: Ticket): Boolean =
        ticket.generation == generation

    @Synchronized
    private fun register(ticket: Ticket, call: Call): Boolean {
        if (ticket.owner !== this || ticket.generation != generation) return false
        active = call
        return true
    }

    @Synchronized
    private fun release(ticket: Ticket, call: Call) {
        if (ticket.owner === this && active === call) active = null
    }
}
