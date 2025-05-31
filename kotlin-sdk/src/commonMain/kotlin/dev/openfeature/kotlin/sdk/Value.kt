@file:OptIn(ExperimentalTime::class)

package dev.openfeature.kotlin.sdk

import kotlin.time.ExperimentalTime

sealed interface Value {

    fun asString(): kotlin.String? = if (this is String) string else null
    fun asBoolean(): kotlin.Boolean? = if (this is Boolean) boolean else null
    fun asInteger(): Int? = if (this is Integer) integer else null
    fun asDouble(): kotlin.Double? = if (this is Double) double else null
    @OptIn(ExperimentalTime::class)
    fun asInstant(): kotlin.time.Instant? = if (this is Instant) instant else null
    fun asList(): kotlin.collections.List<Value>? = if (this is List) list else null
    fun asStructure(): Map<kotlin.String, Value>? = if (this is Structure) structure else null
    fun isNull(): kotlin.Boolean = this is Null

    data class String(val string: kotlin.String) : Value

    data class Boolean(val boolean: kotlin.Boolean) : Value

    data class Integer(val integer: Int) : Value

    data class Double(val double: kotlin.Double) : Value

    data class Instant(val instant: kotlin.time.Instant) : Value

    data class Structure private constructor(val structure: Map<kotlin.String, Value>) : Value {
        companion object {
            private fun deepCopyValue(value: Value): Value {
                return when (value) {
                    is Structure -> Structure(value.structure.mapValues { (_, v) -> deepCopyValue(v) })
                    is List -> List(value.list.map { deepCopyValue(it) })
                    else -> value
                }
            }

            operator fun invoke(structure: Map<kotlin.String, Value>): Structure {
                val deepCopiedStructure = structure.mapValues { (_, value) -> deepCopyValue(value) }
                return Structure(deepCopiedStructure)
            }
        }
    }

    data class List private constructor(val list: kotlin.collections.List<Value>) : Value {
        companion object {
            private fun deepCopyValue(value: Value): Value {
                return when (value) {
                    is Structure -> Structure(value.structure.mapValues { (_, v) -> deepCopyValue(v) })
                    is List -> List(value.list.map { deepCopyValue(it) })
                    else -> value
                }
            }

            operator fun invoke(list: kotlin.collections.List<Value>): List {
                val deepCopiedList = list.map { value -> deepCopyValue(value) }
                return List(deepCopiedList)
            }
        }
    }

    object Null : Value {
        override fun equals(other: Any?): kotlin.Boolean {
            return other is Null
        }

        override fun hashCode(): Int {
            return 0
        }
    }
}