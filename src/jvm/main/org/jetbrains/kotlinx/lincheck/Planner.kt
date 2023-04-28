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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import kotlin.math.*

interface Planner {
    val scenarios: Sequence<ExecutionScenario>
    val iterationsPlanner: IterationsPlanner
    val invocationsPlanner: InvocationsPlanner
    val minimizeFailedScenario: Boolean
}

interface IterationsPlanner {
    fun shouldDoNextIteration(iteration: Int): Boolean
}

interface InvocationsPlanner {
    fun shouldDoNextInvocation(invocation: Int): Boolean
}

fun Planner.runIterations(
    statisticsTracker: StatisticsTracker? = null,
    block: (Int, ExecutionScenario, InvocationsPlanner) -> LincheckFailure?
): LincheckFailure? {
    scenarios.forEachIndexed { i, scenario ->
        if (!iterationsPlanner.shouldDoNextIteration(i))
            return null
        statisticsTracker.trackIteration {
            block(i, scenario, invocationsPlanner)?.let {
                return it
            }
        }
    }
    return null
}

internal class CustomScenariosPlanner(
    val scenariosOptions: List<CustomScenarioOptions>,
) : Planner, IterationsPlanner {

    override val scenarios = scenariosOptions.asSequence().map { it.scenario }

    override val iterationsPlanner = this

    override var invocationsPlanner = FixedInvocationsPlanner(0)
        private set

    override val minimizeFailedScenario: Boolean = false

    override fun shouldDoNextIteration(iteration: Int): Boolean {
        invocationsPlanner = FixedInvocationsPlanner(scenariosOptions[iteration].invocations)
        return iteration < scenariosOptions.size
    }

}

internal class RandomScenariosAdaptivePlanner(
    mode: LincheckMode,
    testingTimeMs: Long,
    val minThreads: Int,
    val maxThreads: Int,
    val minOperations: Int,
    val maxOperations: Int,
    val generateBeforeAndAfterParts: Boolean,
    override val minimizeFailedScenario: Boolean,
    scenarioGenerator: ExecutionGenerator,
    statisticsTracker: StatisticsTracker,
) : Planner {
    private val planner = AdaptivePlanner(mode, testingTimeMs, statisticsTracker)
    override val iterationsPlanner = planner
    override val invocationsPlanner = planner

    private val configurations: List<Pair<Int, Int>> = run {
        (minThreads .. maxThreads).flatMap { threads ->
            (minOperations .. maxOperations).flatMap { operations ->
                listOf(threads to operations)
            }
        }
    }

    override val scenarios = sequence {
        while (true) {
            val n = floor(planner.testingProgress * configurations.size).toInt()
            val (threads, operations) = configurations[min(n, configurations.size - 1)]
            yield(scenarioGenerator.nextExecution(threads, operations,
                if (generateBeforeAndAfterParts) operations else 0,
                if (generateBeforeAndAfterParts) operations else 0,
            ))
        }
    }
}

internal class FixedInvocationsPlanner(val totalInvocations: Int) : InvocationsPlanner {
    override fun shouldDoNextInvocation(invocation: Int) =
        invocation < totalInvocations
}

/**
 * The class for planning the work distribution during one run.
 * In particular, it is responsible for the following activities:
 * - measuring the time of invocations run;
 * - keep the track of deadlines and remaining time;
 * - adaptively adjusting number of test scenarios and invocations allocated per scenario.
 */
internal class AdaptivePlanner(
    /**
     * Testing mode. It is used to calculate upper/lower bounds on the number of invocations.
     */
    mode: LincheckMode,
    /**
     * Total amount of time in milliseconds allocated for testing.
     */
    testingTimeMs: Long,
    /**
     * Statistics tracker.
     */
    val statisticsTracker: StatisticsTracker,
) : IterationsPlanner, InvocationsPlanner {

    /**
     * Total amount of time in nanoseconds allocated for testing.
     */
    var testingTimeNano: Long = testingTimeMs * 1_000_000
        private set

    /**
     * Remaining amount of time for testing.
     */
    val remainingTimeNano: Long
        get() = testingTimeNano - statisticsTracker.runningTimeNano

    /**
     * Testing progress: floating-point number in range [0.0 .. 1.0],
     * representing a fraction of spent testing time.
     */
    val testingProgress: Double
        get() = statisticsTracker.runningTimeNano / testingTimeNano.toDouble()

    /**
     * Upper bound on the number of iterations.
     * Adjusted automatically after each iteration.
     */
    private var iterationsBound = INITIAL_ITERATIONS_BOUND

    /**
     * Number of remaining iterations, calculated based on current iterations bound.
     */
    private val remainingIterations: Int
        get() = iterationsBound - statisticsTracker.iteration


    /**
     * Upper bound on the number of invocations per iteration.
     * Adjusted automatically during each iteration.
     */
    private var invocationsBound = INITIAL_INVOCATIONS_BOUND

    /**
     * Lower bound of invocations allocated by iteration
     */
    private val invocationsLowerBound = INVOCATIONS_LOWER_BOUND

    /**
     * upper bound of invocations allocated by iteration
     */
    private val invocationsUpperBound = when (mode) {
        LincheckMode.Stress         -> STRESS_INVOCATIONS_UPPER_BOUND
        LincheckMode.ModelChecking  -> MODEL_CHECKING_INVOCATIONS_UPPER_BOUND
        else -> throw IllegalArgumentException()
    }

    override fun shouldDoNextIteration(iteration: Int): Boolean {
        check(iteration == statisticsTracker.iteration)
        if (iteration > 0) {
            adjustBounds(
                averageInvocationTimeNano = statisticsTracker.averageInvocationTimeNano(iteration - 1)
            )
        }
        return (remainingTimeNano > 0) && (iteration < iterationsBound)
    }

    override fun shouldDoNextInvocation(invocation: Int): Boolean {
        check(invocation == statisticsTracker.invocation)
        return (remainingTimeNano > 0) && (invocation < invocationsBound)
    }

    /*
     * Adjustment of remaining iteration and invocation bounds.
     * We aim to maintain the following ratio between number of iterations and invocations:
     *     C = M / N
     * where
     *   - C is a ratio constant,
     *   - N is a number of iterations,
     *   - M is a number of invocations.
     *
     * We call this function after each iteration in order to adjust N and M to ensure that the desired ratio is preserved.
     * We estimate average invocation time based on statistics, and then divide
     * remaining time to average invocation time to compute total remaining number of invocations.
     * Then, given that total invocations count T is can be computed as follows:
     *     T = N * M
     * and we have that
     *     N = M / C
     * we derive that number of invocations should be adjusted as follows:
     *     M = sqrt(T * C)
     *
     * If after these calculations, there is significant outrun or delay
     * then we manually add/remove few iterations to better fit into the time constraints
     * (this can happen, for example, when we hit invocation max/min bounds).
     */
    private fun adjustBounds(averageInvocationTimeNano: Double) {
        // calculate invocation and iteration bounds
        val totalInvocations = testingTimeNano / averageInvocationTimeNano
        invocationsBound = sqrt(totalInvocations * INVOCATIONS_TO_ITERATIONS_RATIO).toInt()
            .roundUpTo(INVOCATIONS_FACTOR)
            .coerceAtLeast(invocationsLowerBound)
            .coerceAtMost(invocationsUpperBound)
        // calculate remaining iterations bound
        iterationsBound = (invocationsBound.toDouble() / INVOCATIONS_TO_ITERATIONS_RATIO)
            .let { floor(it).toInt() }
            .coerceAtLeast(statisticsTracker.iteration)

        // adjust the remaining iterations bounds to fit into deadline
        val iterationTimeEstimateNano = invocationsBound * averageInvocationTimeNano
        val remainingTimeEstimateNano = remainingIterations * iterationTimeEstimateNano
        val timeDiffNano = abs(remainingTimeEstimateNano - remainingTimeNano)
        val iterationsDiff = ceil(timeDiffNano / iterationTimeEstimateNano).toInt()
        if (iterationsDiff >= ITERATIONS_DELTA) {
            if (remainingTimeEstimateNano > remainingTimeNano)
                iterationsBound -= iterationsDiff
            else
                iterationsBound += iterationsDiff
        }

        println("iterationsBound=$iterationsBound, invocationsBound=$invocationsBound")
    }

    companion object {
        // initial iterations upper bound
        private const val INITIAL_ITERATIONS_BOUND = 30

        // number of iterations added/subtracted when we over- or under-perform the plan
        private const val ITERATIONS_DELTA = 1

        // initial number of invocations
        private const val INITIAL_INVOCATIONS_BOUND = 1_000

        // number of invocations should be divisible to this constant,
        // that is we ensure number of invocations is always rounded up to this constant
        internal const val INVOCATIONS_FACTOR = 100

        internal const val INVOCATIONS_TO_ITERATIONS_RATIO = 100

        internal const val INVOCATIONS_LOWER_BOUND = 1_000
        internal const val STRESS_INVOCATIONS_UPPER_BOUND = 1_000_000
        internal const val MODEL_CHECKING_INVOCATIONS_UPPER_BOUND = 20_000
    }

}

private fun Double.roundUpTo(c: Double) = round(this / c) * c
private fun Int.roundUpTo(c: Int) = toDouble().roundUpTo(c.toDouble()).toInt()

private fun Double.ceilUpTo(c: Double) = ceil(this / c) * c
private fun Int.ceilUpTo(c: Int) = toDouble().ceilUpTo(c.toDouble()).toInt()