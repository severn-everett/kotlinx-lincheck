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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyStateHolder
import org.jetbrains.kotlinx.lincheck.strategy.managed.getObjectNumber
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.coroutines.Continuation

fun main() {
    val m = HashMap<Int, String>()
    m.put(3, "aaa")
    m.put(4, "bbb")
    m.put(5, "ccc")
    println(visualize(m, "HashMap"))
}

fun visualize(obj: Any, rootName: String): String {
    val s = StringBuilder()
    s.append("@startuml")

    s.append("@enduml")
    return s.toString()
}

private fun title(obj: Any?) {
    
}

// Try to construct a string representation
private fun stringRepresentation(obj: Any?): String? {
    if (obj == null || obj.javaClass.isImmutableWithNiceToString)
        return obj.toString()
    val id = getObjectNumber(obj.javaClass, obj)
    if (obj is Continuation<*>) {
        val runner = (ManagedStrategyStateHolder.strategy as? ModelCheckingStrategy)?.runner as? ParallelThreadsRunner
        val thread = runner?.executor?.threads?.find { it.cont === obj }
        return if (thread == null) "cont@$id" else "cont@$id[Thread-${thread.iThread + 1}]"
    }
    return null
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val Class<out Any>.isImmutableWithNiceToString
    get() = this.canonicalName in listOf(
        java.lang.Integer::class.java,
        java.lang.Long::class.java,
        java.lang.Short::class.java,
        java.lang.Double::class.java,
        java.lang.Float::class.java,
        java.lang.Character::class.java,
        java.lang.Byte::class.java,
        java.lang.Boolean::class.java,
        java.lang.String::class.java,
        BigInteger::class.java,
        BigDecimal::class.java,
        kotlinx.coroutines.internal.Symbol::class.java,
    ).map { it.canonicalName }