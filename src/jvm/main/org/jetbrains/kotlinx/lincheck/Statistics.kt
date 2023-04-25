/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck

interface Statistics {

    /**
     * Total amount of time already spent on testing.
     */
    val runningTimeNano: Long

    /**
     * An array keeping running time of all iterations.
     */
    val iterationsRunningTimeNano: List<Long>

    /**
     * Array keeping number of invocations executed for each iteration
     */
    val iterationsInvocationsCount: List<Int>

}

/**
 * Average invocation time on given [iteration].
 */
fun Statistics.averageInvocationTimeNano(iteration: Int): Double =
    iterationsRunningTimeNano[iteration] / iterationsInvocationsCount[iteration].toDouble()

class StatisticsTracker : Statistics {

    override var runningTimeNano: Long = 0
        private set

    override val iterationsRunningTimeNano: List<Long>
        get() = _iterationsRunningTimeNano
    private val _iterationsRunningTimeNano = mutableListOf<Long>(0)

    override val iterationsInvocationsCount: List<Int>
        get() = _iterationsInvocationCount
    private val _iterationsInvocationCount = mutableListOf<Int>(0)

    /**
     * Current iteration number.
     */
    val iteration: Int
        get() = iterationsRunningTimeNano.lastIndex

    /**
     * Current invocation number within current iteration.
     */
    val invocation: Int
        get() = iterationsInvocationsCount[iteration]

    /**
     * Running time of current iteration.
     */
    val currentIterationRunningTimeNano: Long
        get() = iterationsRunningTimeNano[iteration]

    /**
     * Number of invocations in current iteration.
     */
    val currentIterationInvocationsCount: Int
        get() = iterationsInvocationsCount[iteration]

    fun iterationStart() {}

    fun iterationEnd() {
        _iterationsRunningTimeNano.add(0)
        _iterationsInvocationCount.add(0)
    }

    fun invocationStart() {}

    fun invocationEnd(invocationTimeNano: Long) {
        runningTimeNano += invocationTimeNano
        _iterationsInvocationCount[iteration] += 1
        _iterationsRunningTimeNano[iteration] += invocationTimeNano
    }

}

inline fun<T> StatisticsTracker?.trackIteration(block: () -> T): T {
    this?.iterationStart()
    try {
        return block()
    } finally {
        this?.iterationEnd()
    }
}

inline fun<T> StatisticsTracker?.trackInvocation(block: () -> T): T {
    val startTimeNano = System.nanoTime()
    this?.invocationStart()
    try {
        return block()
    } finally {
        this?.invocationEnd(System.nanoTime() - startTimeNano)
    }
}