package dev.openfeature.sdk

import kotlin.time.ExperimentalTime

class ImmutableStructure(attributes: Map<String, Value> = mapOf()) : Structure {
    private val attributes: Map<String, Value> = attributes.toMap()

    constructor(vararg pairs: Pair<String, Value>) : this(pairs.toMap())

    override fun keySet(): Set<String> {
        return attributes.keys
    }

    override fun getValue(key: String): Value? {
        return attributes[key]
    }

    override fun asMap(): Map<String, Value> {
        return attributes.toMap()
    }

    override fun asObjectMap(): Map<String, Any?> {
        return attributes.mapValues { convertValue(it.value) }.toMap()
    }

    @OptIn(ExperimentalTime::class)
    private fun convertValue(value: Value): Any? {
        return when (value) {
            is Value.List -> value.list.map { t -> convertValue(t) }.toList()
            is Value.Structure -> value.structure.mapValues { t -> convertValue(t.value) }.toMap()
            is Value.Null -> return null
            is Value.String -> value.asString()
            is Value.Boolean -> value.asBoolean()
            is Value.Integer -> value.asInteger()
            is Value.Instant -> value.asInstant()
            is Value.Double -> value.asDouble()
        }
    }

    override fun hashCode(): Int {
        return attributes.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImmutableStructure) return false

        if (attributes != other.attributes) return false

        return true
    }
}