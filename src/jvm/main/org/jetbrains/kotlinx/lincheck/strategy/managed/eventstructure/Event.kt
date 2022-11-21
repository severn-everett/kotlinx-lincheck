/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

typealias EventID = Int

class Event private constructor(
    val id: EventID,
    /**
     * Event's thread
     */
    val threadId: Int,
    /**
     * Event's position in a thread
     * (i.e. number of its program-order predecessors).
     */
    val threadPosition: Int = 0,
    /**
     * Event's label.
     */
    val label: EventLabel = EmptyLabel(),
    /**
     * Event's parent in program order.
     */
    val parent: Event? = null,
    /**
     * List of event's dependencies
     * (e.g. reads-from write for a read event).
     */
    val dependencies: List<Event> = listOf(),
    /**
     * Vector clock to track causality relation.
     */
    val causalityClock: VectorClock<Int, Event>,
    /**
     * State of the execution frontier at the point when event is created.
     */
    val frontier: ExecutionFrontier,
    /**
     * Frontier of pinned events.
     * Pinned events are the events that should not be
     * considering for branching in an exploration starting from this event.
     */
    val pinnedEvents: ExecutionFrontier,
) : Comparable<Event> {

    var visited: Boolean = false
        private set

    private val jumps = Array<Event?>(N_JUMPS) { null }

    // should only be called from EventStructure
    // TODO: enforce this invariant!
    fun visit() {
        visited = true
    }

    companion object {
        private var nextId: EventID = 0

        fun create(
            threadId: Int,
            label: EventLabel,
            parent: Event?,
            dependencies: List<Event>,
            frontier: ExecutionFrontier,
            pinnedEvents: ExecutionFrontier
        ): Event {
            val id = nextId++
            val threadPosition = parent?.let { it.threadPosition + 1 } ?: 0
            val causalityClock = dependencies.fold(parent?.causalityClock?.copy() ?: emptyClock()) { clock, event ->
                clock + event.causalityClock
            }
            return Event(
                id,
                threadId = threadId,
                threadPosition = threadPosition,
                label = label,
                parent = parent,
                dependencies = dependencies,
                causalityClock = causalityClock,
                frontier = frontier,
                pinnedEvents = pinnedEvents,
            ).apply {
                calculateJumps(this)
                causalityClock.update(threadId, this)
                frontier[threadId] = this
                pinnedEvents.merge(causalityClock.toFrontier())
            }
        }

        private const val N_JUMPS = 10
        private const val MAX_JUMP = 1 shl (N_JUMPS - 1)

        private fun calculateJumps(event: Event) {
            require(N_JUMPS > 0)
            event.jumps[0] = event.parent
            for (i in 1 until N_JUMPS) {
                event.jumps[i] = event.jumps[i - 1]?.jumps?.get(i - 1)
            }
        }

    }

    // naive implementation with O(N) complexity, just for testing and debugging
    private fun predNthNaive(n : Int): Event? {
        var e = this
        // current implementation has O(N) complexity,
        // as an optimization, we can implement binary lifting and get O(lgN) complexity
        // https://cp-algorithms.com/graph/lca_binary_lifting.html;
        // since `predNth` is used to compute programOrder
        // this optimization might be crucial for performance
        for (i in 0 until n)
            e = e.parent ?: return null
        return e
    }

    // binary lifting search with O(lgN) complexity
    // https://cp-algorithms.com/graph/lca_binary_lifting.html;
    private fun predNthOptimized(n: Int): Event? {
        require(n > 0)
        var e = this
        var r = n
        while (r > MAX_JUMP) {
            e = e.jumps[N_JUMPS - 1] ?: return null
            r -= MAX_JUMP
        }
        while (r != 0) {
            val k = 31 - Integer.numberOfLeadingZeros(r)
            val jump = Integer.highestOneBit(r)
            e = e.jumps[k] ?: return null
            r -= jump
        }
        return e
    }

    fun predNth(n: Int): Event? {
        return predNthOptimized(n)
            // .also { check(it == predNthNaive(n)) }
    }

    val readsFrom: Event by lazy {
        require(label is ReadAccessLabel && label.isResponse)
        check(dependencies.isNotEmpty())
        dependencies.first().also {
            // TODO: make `isSynchronized` method to check for labels' compatibility
            //  according to synchronization algebra (e.g. write/read reads-from compatibility)
            check((it.label is InitializationLabel) ||
                  (it.label is MemoryAccessLabel && it.label.isWrite &&
                   it.label.location == label.location))
        }
    }

    val exclusiveReadPart: Event by lazy {
        require(label is WriteAccessLabel && label.isExclusive)
        check(parent != null)
        parent.also {
            check(it.label is ReadAccessLabel && it.label.isResponse
                && it.label.location == label.location
                && it.label.isExclusive
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        return (other is Event) && (id == other.id)
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun compareTo(other: Event): Int {
        return id.compareTo(other.id)
    }

    override fun toString(): String {
        return "#${id}: [${threadId}, ${threadPosition}] $label"
    }
}

val programOrder: PartialOrder<Event> = PartialOrder.ofLessThan { x, y ->
    if (x.threadId != y.threadId || x.threadPosition >= y.threadPosition)
        false
    else (x == y.predNth(y.threadPosition - x.threadPosition))
}

val causalityOrder: PartialOrder<Event> = PartialOrder.ofLessOrEqual { x, y ->
    y.causalityClock.observes(x.threadId, x)
}

val externalCausalityCovering: Covering<Event> = Covering { y ->
    y.dependencies
}

fun emptyClock() = VectorClock<Int, Event>(programOrder)