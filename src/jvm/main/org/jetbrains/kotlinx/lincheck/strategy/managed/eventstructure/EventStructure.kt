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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import kotlin.reflect.KClass

typealias ThreadSwitchCallback = (Int) -> Unit

class EventStructure(
    nThreads: Int,
    val checker: ConsistencyChecker = idleConsistencyChecker,
    val incrementalChecker: IncrementalConsistencyChecker = idleIncrementalConsistencyChecker,
) {
    val initialThreadId = nThreads
    val rootThreadId = nThreads + 1
    val maxThreadId = rootThreadId

    val root: Event

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private val _events: SortedArrayList<Event> = sortedArrayListOf()

    /**
     * List of events of the event structure.
     */
    val events: SortedList<Event> = _events

    lateinit var currentExplorationRoot: Event
        private set

    // TODO: this pattern is covered by explicit backing fields KEEP
    //   https://github.com/Kotlin/KEEP/issues/278
    private var _currentExecution: MutableExecution = MutableExecution()

    val currentExecution: Execution
        get() = _currentExecution

    private var playedFrontier: ExecutionFrontier = ExecutionFrontier()

    private var pinnedEvents: ExecutionFrontier = ExecutionFrontier()

    private val delayedConsistencyCheckBuffer = mutableListOf<Event>()

    private var detectedInconsistency: Inconsistency? = null

    init {
        root = addRootEvent()
    }

    private fun emptyFrontier(): ExecutionFrontier =
        ExecutionFrontier().apply { set(rootThreadId, root) }

    private fun emptyExecution(): Execution =
        emptyFrontier().toExecution()

    fun getThreadRoot(iThread: Int): Event? =
        currentExecution.firstEvent(iThread)?.also { event ->
            check(event.label is ThreadStartLabel && event.label.isRequest)
        }

    fun isStartedThread(iThread: Int): Boolean =
        getThreadRoot(iThread) != null

    fun isFinishedThread(iThread: Int): Boolean =
        currentExecution.lastEvent(iThread)?.let { event ->
            event.label is ThreadFinishLabel
        } ?: false

    fun startNextExploration(): Boolean {
        loop@while (true) {
            val event = rollbackToEvent { !it.visited }?.apply { visit() }
                ?: return false
            resetExploration(event)
            return true
        }
    }

    fun initializeExploration() {
        playedFrontier = emptyFrontier()
    }

    fun abortExploration() {
        _currentExecution = playedFrontier.toExecution()
    }

    private fun rollbackToEvent(predicate: (Event) -> Boolean): Event? {
        val eventIdx = _events.indexOfLast(predicate)
        _events.subList(eventIdx + 1, _events.size).clear()
        return events.lastOrNull()
    }

    private fun resetExploration(event: Event) {
        check(delayedConsistencyCheckBuffer.isEmpty())
        currentExplorationRoot = event
        _currentExecution = event.frontier.toExecution()
        pinnedEvents = event.pinnedEvents.copy()
        detectedInconsistency = null
    }

    fun checkConsistency(): Inconsistency? {
        if (detectedInconsistency == null) {
            detectedInconsistency = checker.check(currentExecution)
        }
        return detectedInconsistency
    }

    private fun checkConsistencyIncrementally(event: Event, isReplayedEvent: Boolean): Inconsistency? {
        if (inReplayPhase()) {
            // If we are in replay phase, but the event being added is not a replayed event,
            // then we need to save it to delayed events consistency check buffer,
            // so that we will be able to pass it to incremental consistency checker later.
            // This situation can occur when we are replaying instruction that produces several events.
            // For example, replaying the read part of read-modify-write instruction (like CAS)
            // can also create new event representing write part of this RMW.
            if (!isReplayedEvent) {
                delayedConsistencyCheckBuffer.add(event)
            }
            // In any case we do not run incremental consistency checks during replay phase,
            // because during this phase consistency checker has invalid internal state.
            return null
        }
        // If we are not in replay phase anymore, but the current event is replayed event,
        // it means that we just finished replay phase (i.e. the given event is the last replayed event).
        // In this case we need to do the following.
        //   (1) Reset internal state of incremental checker.
        //   (2) Run incremental checker on all delayed non-replayed events.
        //   (3) Check full consistency of the new execution before we start to explore it further.
        // We run incremental consistency checker before heavyweight full consistency check
        // in order to give it more lightweight incremental checker
        // an opportunity to find inconsistency earlier.
        if (isReplayedEvent) {
            val replayedExecution = currentExplorationRoot.frontier.toExecution()
            // we temporarily remove new event in order to reset incremental checker
            replayedExecution.removeLastEvent(currentExplorationRoot)
            // reset internal state of incremental checker
            incrementalChecker.reset(replayedExecution)
            // add current exploration root to delayed buffer too
            delayedConsistencyCheckBuffer.add(currentExplorationRoot)
            // copy delayed events from the buffer and reset it
            val delayedEvents = delayedConsistencyCheckBuffer.toMutableList()
            delayedConsistencyCheckBuffer.clear()
            // run incremental checker on delayed events
            for (delayedEvent in delayedEvents) {
                replayedExecution.addEvent(delayedEvent)
                incrementalChecker.check(delayedEvent)?.let { return it }
            }
            // to make sure that we have incrementally checked all newly added events
            check(replayedExecution == currentExecution)
            // finally run heavyweight full consistency check
            return checker.check(_currentExecution)
        }
        // If we are not in replay phase (and we have finished it before adding current event)
        // then just run incremental consistency checker.
        return incrementalChecker.check(event)
    }

    fun inReplayPhase(): Boolean =
        (0 .. maxThreadId).any { inReplayPhase(it) }

    fun inReplayPhase(iThread: Int): Boolean {
        val frontEvent = playedFrontier[iThread]?.also { check(it in _currentExecution) }
        return (frontEvent != currentExecution.lastEvent(iThread))
    }

    // should only be called in replay phase!
    fun canReplayNextEvent(iThread: Int): Boolean {
        val nextPosition = playedFrontier.getNextPosition(iThread)
        val atomicEvent = currentExecution.nextAtomicEvent(iThread, nextPosition, replaying = true)!!
        // delay replaying the last event till all other events are replayed;
        if (currentExplorationRoot == atomicEvent.events.last()) {
            // TODO: prettify
            return (0 .. maxThreadId).all { it == iThread || !inReplayPhase(it) }
        }
        return atomicEvent.dependencies.all { dependency ->
            dependency in playedFrontier
        }
    }

    private fun tryReplayEvent(iThread: Int): Event? {
        return if (inReplayPhase(iThread)) {
            val position = 1 + playedFrontier.getPosition(iThread)
            check(position < currentExecution.getThreadSize(iThread))
            currentExecution[iThread, position]!!
        } else null
    }

    private fun createEvent(iThread: Int, label: EventLabel, parent: Event?, dependencies: List<Event>): Event {
        // To prevent causality cycles to appear we check that
        // dependencies do not causally depend on predecessor.
        check(dependencies.all { dependency -> !causalityOrder.lessThan(parent!!, dependency) })
        val cutPosition = parent?.let { it.threadPosition + 1 } ?: 0
        return Event.create(
            threadId = iThread,
            label = label,
            parent = parent,
            // TODO: rename to external dependencies?
            dependencies = dependencies.filter { it != parent },
            frontier = cutFrontier(iThread, cutPosition, currentExecution.toFrontier()),
            pinnedEvents = cutFrontier(iThread, cutPosition, pinnedEvents),
        )
    }

    private fun cutFrontier(iThread: Int, position: Int, frontier: ExecutionFrontier): ExecutionFrontier {
        // Calculating cut event:
        // first try to obtain event at given position in the current execution
        val cutEvent = currentExecution[iThread, position]
            ?: return frontier.copy()
        return ExecutionFrontier(frontier.mapping.mapNotNull { (threadId, frontEvent) ->
            require(frontEvent in currentExecution)
            // TODO: optimize using binary search
            var event: Event = frontEvent
            while (event.causalityClock.observes(cutEvent.threadId, cutEvent)) {
                event = event.parent ?: return@mapNotNull null
            }
            threadId to event
        }.toMap())
    }

    private fun addEvent(iThread: Int, label: EventLabel, parent: Event?, dependencies: List<Event>): Event {
        return createEvent(iThread, label, parent,  dependencies).also { event ->
            _events.add(event)
        }
    }

    private fun addEventToCurrentExecution(event: Event, visit: Boolean = true, synchronize: Boolean = false) {
        if (visit) { event.visit() }
        val isReplayedEvent = inReplayPhase(event.threadId)
        if (!isReplayedEvent)
            _currentExecution.addEvent(event)
        playedFrontier.update(event)
        if (synchronize)
            addSynchronizedEvents(event)
        // TODO: set suddenInvocationResult instead
        if (detectedInconsistency == null) {
            detectedInconsistency = checkConsistencyIncrementally(event, isReplayedEvent)
        }
    }

    private fun synchronizationCandidates(event: Event): List<Event> {
        val predicates = mutableListOf<(Event) -> Boolean>()

        // for send event we filter out all of its causal predecessors,
        // because an attempt to synchronize with these predecessors will result in causality cycle
        if (event.label.isSend) {
            predicates.add { !causalityOrder.lessThan(it, event) && !pinnedEvents.contains(it) }
        }

        // for read-request events we search for the last write to the same memory location
        // in the same thread, and then filter out all causal predecessors of this last write,
        // because these events are "obsolete" --- reading from them will result in coherence cycle
        // and will violate consistency
        // TODO: we can improve on this and calculate the vector clock
        //   of events observed in the reader thread at current position
        if (event.label.isRequest && event.label is MemoryAccessLabel) {
            require(event.label.isRead)
            val threadLastWrite = currentExecution[event.threadId]?.lastOrNull {
                it.label is WriteAccessLabel && it.label.location == event.label.location
            } ?: root
            predicates.add { !causalityOrder.lessThan(it, threadLastWrite) }
        }

        return currentExecution.filter {
            for (predicate in predicates) {
                if (!predicate(it))
                    return@filter false
            }
            return@filter true
        }
    }

    /**
     * Adds to the event structure a list of events obtained as a result of synchronizing given [event]
     * with the events contained in the current exploration. For example, if
     * `e1 @ A` is the given event labeled by `A` and `e2 @ B` is some event in the event structure labeled by `B`,
     * then the resulting list will contain event labeled by `C = A \+ B` if `C` is defined (i.e. not null),
     * and the list of dependencies of this new event will be equal to `listOf(e1, e2)`.
     *
     * @return list of added events
     */
    private fun addSynchronizedEvents(event: Event): List<Event> {
        // TODO: we should maintain an index of read/write accesses to specific memory location
        val candidateEvents = synchronizationCandidates(event)
        val syncEvents = arrayListOf<Event>()
        when(event.label.syncType) {
            SynchronizationType.Binary -> addBinarySynchronizedEvents(event, candidateEvents).let {
                syncEvents.addAll(it)
            }
            SynchronizationType.Barrier -> addBarrierSynchronizedEvents(event, candidateEvents)?.let {
                syncEvents.add(it)
            }
        }
        return syncEvents
    }

    private fun addBinarySynchronizedEvents(event: Event, candidateEvents: Collection<Event>): List<Event> {
        require(event.label.isBinarySynchronizing)
        // TODO: sort resulting events according to some strategy?
        return candidateEvents.mapNotNull { other ->
            val syncLab = event.label.synchronize(other.label) ?: return@mapNotNull null
            val (parent, dependency) = when {
                event.label.isRequest -> event to other
                other.label.isRequest -> other to event
                else -> unreachable()
            }
            check(parent.label.isRequest && dependency.label.isSend && syncLab.isResponse)
            addEvent(parent.threadId, syncLab, parent, dependencies = listOf(dependency))
        }
    }

    private fun addBarrierSynchronizedEvents(event: Event, candidateEvents: Collection<Event>): Event? {
        require(event.label.isBarrierSynchronizing)
        val (syncLab, dependencies) =
            candidateEvents.fold(event.label to listOf(event)) { (lab, deps), candidateEvent ->
                candidateEvent.label.synchronize(lab)?.let {
                    (it to deps + candidateEvent)
                } ?: (lab to deps)
            }
        if (syncLab.isBlocking && !syncLab.unblocked)
            return null
        // We assume that at most one of the events participating into synchronization
        // is a request event, and the result of synchronization is response event.
        check(syncLab.isResponse)
        val parent = dependencies.first { it.label.isRequest }
        return addEvent(parent.threadId, syncLab, parent, dependencies.filter { it != parent })
    }

    private fun addRootEvent(): Event {
        // we do not mark root event as visited purposefully;
        // this is just a trick to make first call to `startNextExploration`
        // to pick the root event as the next event to explore from.
        val label = InitializationLabel()
        return addEvent(rootThreadId, label, parent = null, dependencies = emptyList()).also {
            addEventToCurrentExecution(it, visit = false)
        }
    }

    private fun addSendEvent(iThread: Int, label: EventLabel): Event {
        require(label.isSend)
        tryReplayEvent(iThread)?.let { event ->
            // TODO: also check custom event/label specific rules when replaying,
            //   e.g. upon replaying write-exclusive check its location equal to
            //   the location of previous read-exclusive part
            event.label.replay(label).also { check(it) }
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList()).also { event ->
            addEventToCurrentExecution(event, synchronize = true)
        }
    }

    private fun addRequestEvent(iThread: Int, label: EventLabel): Event {
        require(label.isRequest)
        tryReplayEvent(iThread)?.let { event ->
            event.label.replay(label).also { check(it) }
            addEventToCurrentExecution(event)
            return event
        }
        val parent = playedFrontier[iThread]
        return addEvent(iThread, label, parent, dependencies = emptyList()).also { event ->
            addEventToCurrentExecution(event)
        }
    }

    private fun addResponseEvents(requestEvent: Event): Pair<Event?, List<Event>> {
        require(requestEvent.label.isRequest)
        tryReplayEvent(requestEvent.threadId)?.let { event ->
            check(event.label.isResponse)
            check(event.parent == requestEvent)
            val label = event.dependencies.fold (event.parent.label) { label: EventLabel?, dependency ->
                label?.synchronize(dependency.label)
            }
            check(label != null)
            event.label.replay(label).also { check(it) }
            addEventToCurrentExecution(event)
            return event to listOf(event)
        }
        val responseEvents = addSynchronizedEvents(requestEvent)
        // TODO: use some other strategy to select the next event in the current exploration?
        // TODO: check consistency of chosen event!
        val chosenEvent = responseEvents.lastOrNull()?.also { event ->
            addEventToCurrentExecution(event)
        }
        return (chosenEvent to responseEvents)
    }

    fun addThreadStartEvent(iThread: Int): Event {
        val label = ThreadStartLabel(
            threadId = iThread,
            kind = LabelKind.Request,
            isMainThread = (iThread == initialThreadId)
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addThreadFinishEvent(iThread: Int): Event {
        val label = ThreadFinishLabel(
            threadId = iThread,
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadForkEvent(iThread: Int, forkThreadIds: Set<Int>): Event {
        val label = ThreadForkLabel(
            forkThreadIds = forkThreadIds
        )
        return addSendEvent(iThread, label)
    }

    fun addThreadJoinEvent(iThread: Int, joinThreadIds: Set<Int>): Event {
        val label = ThreadJoinLabel(
            kind = LabelKind.Request,
            joinThreadIds = joinThreadIds,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, responseEvents) = addResponseEvents(requestEvent)
        // TODO: handle case when ThreadJoin is not ready yet
        checkNotNull(responseEvent)
        check(responseEvents.size == 1)
        return responseEvent
    }

    fun addWriteEvent(iThread: Int, location: MemoryLocation, value: OpaqueValue?, kClass: KClass<*>,
                      isExclusive: Boolean = false): Event {
        val label = WriteAccessLabel(
            location_ = location,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        return addSendEvent(iThread, label)
    }

    fun addReadEvent(iThread: Int, location: MemoryLocation, kClass: KClass<*>,
                     isExclusive: Boolean = false): Event {
        // we first create read-request event with unknown (null) value,
        // value will be filled later in read-response event
        val label = ReadAccessLabel(
            kind = LabelKind.Request,
            location_ = location,
            value_ = null,
            kClass = kClass,
            isExclusive = isExclusive,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        // TODO: think again --- is it possible that there is no write to read-from?
        //  Probably not, because in Kotlin variables are always initialized by default?
        //  What about initialization-related issues?
        checkNotNull(responseEvent)
        return responseEvent
    }

    fun addLockEvent(iThread: Int, mutex: Any): Event {
        val label = LockLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        return responseEvent
    }

    fun addUnlockEvent(iThread: Int, mutex: Any): Event {
        val label = UnlockLabel(mutex)
        return addSendEvent(iThread, label)
    }

    fun addWaitEvent(iThread: Int, mutex: Any): Event {
        val label = WaitLabel(
            kind = LabelKind.Request,
            mutex_ = mutex,
        )
        val requestEvent = addRequestEvent(iThread, label)
        val (responseEvent, _) = addResponseEvents(requestEvent)
        checkNotNull(responseEvent)
        return responseEvent
    }

    fun addNotifyEvent(iThread: Int, mutex: Any, isBroadcast: Boolean): Event {
        // TODO: we currently ignore isBroadcast flag and handle `notify` similarly as `notifyAll`.
        //   It is correct wrt. Java's semantics, since `wait` can wake-up spuriously according to the spec.
        //   Thus multiple wake-ups due to single notify can be interpreted as spurious.
        //   However, if one day we will want to support wait semantics without spurious wake-ups
        //   we will need to revisit this.
        val label = NotifyLabel(mutex, isBroadcast)
        return addSendEvent(iThread, label)
    }

}