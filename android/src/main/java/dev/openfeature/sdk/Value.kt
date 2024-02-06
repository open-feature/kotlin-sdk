package dev.openfeature.sdk

sealed interface Value {

    fun asString(): kotlin.String? = if (this is String) string else null
    fun asBoolean(): kotlin.Boolean? = if (this is Boolean) boolean else null
    fun asInteger(): Int? = if (this is Integer) integer else null
    fun asDouble(): kotlin.Double? = if (this is Double) double else null
    fun asDate(): java.util.Date? = if (this is Date) date else null
    fun asList(): kotlin.collections.List<Value>? = if (this is List) list else null
    fun asStructure(): Map<kotlin.String, Value>? = if (this is Structure) structure else null
    fun isNull(): kotlin.Boolean = this is Null

    data class String(val string: kotlin.String) : Value

    data class Boolean(val boolean: kotlin.Boolean) : Value

    data class Integer(val integer: Int) : Value

    data class Double(val double: kotlin.Double) : Value

    data class Date(val date: java.util.Date) : Value

    data class Structure(val structure: Map<kotlin.String, Value>) : Value

    data class List(val list: kotlin.collections.List<Value>) : Value

    object Null : Value {
        override fun equals(other: Any?): kotlin.Boolean {
            return other is Null
        }

        override fun hashCode(): Int {
            return javaClass.hashCode()
        }
    }
}