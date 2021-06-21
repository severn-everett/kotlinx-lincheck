/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_CHECK_OBSTRUCTION_FREEDOM
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_ELIMINATE_LOCAL_OBJECTS
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_GUARANTEES
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_HANGING_DETECTION_THRESHOLD
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_INVOCATIONS
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_VERBOSE_TRACE
import java.util.*

/**
 * Common options for all managed strategies.
 */
abstract class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration<*, *>> : Options<OPT, CTEST>() {
    protected var invocationsPerIteration = DEFAULT_INVOCATIONS
    protected var checkObstructionFreedom = DEFAULT_CHECK_OBSTRUCTION_FREEDOM
    protected var hangingDetectionThreshold = DEFAULT_HANGING_DETECTION_THRESHOLD
    protected val guarantees: MutableList<ManagedStrategyGuarantee> = ArrayList(DEFAULT_GUARANTEES)
    protected var eliminateLocalObjects: Boolean = DEFAULT_ELIMINATE_LOCAL_OBJECTS;
    protected var verboseTrace = DEFAULT_VERBOSE_TRACE

    /**
     * Use the specified number of scenario invocations to study interleavings in each iteration.
     * Lincheck can use less invocations if it requires less ones to study all possible interleavings.
     */
    fun invocationsPerIteration(invocations: Int): OPT = applyAndCast {
        invocationsPerIteration = invocations
    }

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    fun checkObstructionFreedom(checkObstructionFreedom: Boolean = true): OPT = applyAndCast {
        this.checkObstructionFreedom = checkObstructionFreedom
    }

    /**
     * Use the specified maximum number of repetitions to detect endless loops (hangs).
     * A found loop will force managed execution to switch the executing thread or report
     * ab obstruction-freedom violation if [checkObstructionFreedom] is set.
     */
    fun hangingDetectionThreshold(hangingDetectionThreshold: Int): OPT = applyAndCast {
        this.hangingDetectionThreshold = hangingDetectionThreshold
    }

    /**
     * Add a guarantee that methods in some classes are either correct in terms of concurrent execution or irrelevant.
     * These guarantees can be used for optimization. For example, we can add a guarantee that all the methods
     * in `java.util.concurrent.ConcurrentHashMap` are correct and this way the strategy will not try to switch threads
     * inside these methods. We can also mark methods irrelevant (e.g., in logging classes) so that they will be
     * completely ignored (so that they will neither be treated as atomic nor interrupted in the middle) while
     * studying possible interleavings.
     */
    fun addGuarantee(guarantee: ManagedStrategyGuarantee): OPT = applyAndCast {
        guarantees.add(guarantee)
    }

    /**
     * Set to `true` to make Lincheck log all events in an incorrect execution trace.
     * By default, Lincheck collapses the method invocations that were not interrupted
     * (e.g., due to a switch to another thread), and omits all the details except for
     * the method invocation result.
     */
    fun verboseTrace(verboseTrace: Boolean = true): OPT = applyAndCast {
        this.verboseTrace = verboseTrace
    }

    /**
     * Internal, DO NOT USE.
     */
    internal fun eliminateLocalObjects(eliminateLocalObjects: Boolean) {
        this.eliminateLocalObjects = eliminateLocalObjects;
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration<*, *>> ManagedOptions<OPT, CTEST>.applyAndCast(
                block: ManagedOptions<OPT, CTEST>.() -> Unit
        ) = this.apply {
            block()
        } as OPT
    }
}