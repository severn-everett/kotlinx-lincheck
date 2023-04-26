/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.*
import kotlin.math.*
import kotlin.reflect.*

abstract class AbstractLincheckTest(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) : VerifierState() {

    @Test(timeout = TIMEOUT)
    fun testWithStressStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.Stress
        configure()
    }.runTest()

    @Test(timeout = TIMEOUT)
    fun testWithModelCheckingStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.ModelChecking
        configure()
    }.runTest()

    @Test(timeout = TIMEOUT)
    fun testWithHybridStrategy(): Unit = LincheckOptions {
        this as LincheckOptionsImpl
        mode = LincheckMode.Hybrid
        configure()
    }.runTest()

    private fun LincheckOptions.runTest() {
        val result = runTests(this@AbstractLincheckTest::class.java)
        val failure = result.failure
        if (failure == null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
        checkAdaptivePlanningConstraints(result)
    }

    private fun LincheckOptions.checkAdaptivePlanningConstraints(result: LincheckTestingResult) {
        // the failure can be detected earlier, thus it is fine if the planning constraints are violated
        if (result.failure != null)
            return
        val statistics = result.statistics
        val testingTimeNano = testingTimeInSeconds * 1_000_000_000
        // error up to 0.25 sec
        val deltaNano = 1_000_000_000 / 4
        // check that the actual running time is close to specified time
        assert(abs(testingTimeNano - statistics.runningTimeNano) < deltaNano) { """
            Testing time is beyond expected bounds:
            actual: ${String.format("%.3f", statistics.runningTimeNano.toDouble() / 1_000_000_000)}.
            expected: ${String.format("%.3f", testingTimeNano.toDouble() / 1_000_000_000)}
        """.trimIndent()
        }
        // check that the invocations / iterations ratio between is constant;
        // however, if most iterations have invocations number close to the bounds,
        // then we do not check ratio constraint, because it is likely to be violated
        val invocationsBounds = listOf(
            AdaptivePlanner.INVOCATIONS_LOWER_BOUND,
            AdaptivePlanner.STRESS_INVOCATIONS_UPPER_BOUND,
            AdaptivePlanner.MODEL_CHECKING_INVOCATIONS_UPPER_BOUND,
        )
        val boundsReachingIterations = statistics.iterationsInvocationsCount
            .count { it in invocationsBounds }
        if (boundsReachingIterations > 0.6 * statistics.iterations)
            return
        val invocationsRatio = statistics.averageInvocations / statistics.iterations
        val expectedRatio = AdaptivePlanner.INVOCATIONS_TO_ITERATIONS_RATIO.toDouble()
        assert(abs(invocationsRatio - expectedRatio) < expectedRatio * 0.05) { """
            Invocations to iterations ratio differs from expected:
            actual: ${String.format("%.3f", invocationsRatio)}
            expected: $expectedRatio
        """.trimIndent()
        }
    }

    private fun LincheckOptionsImpl.configure() {
        testingTimeInSeconds = 5
        maxThreads = 3
        maxOperationsInThread = 2
        minimizeFailedScenario = false
        customize()
    }

    internal open fun LincheckOptionsImpl.customize() {}

    override fun extractState(): Any = System.identityHashCode(this)

}

private const val TIMEOUT = 100_000L

fun checkTraceHasNoLincheckEvents(trace: String) {
    val testPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.test.").size - 1
    val lincheckPackageOccurrences = trace.split("org.jetbrains.kotlinx.lincheck.").size - 1
    check(testPackageOccurrences == lincheckPackageOccurrences) { "Internal Lincheck events were found in the trace" }
}