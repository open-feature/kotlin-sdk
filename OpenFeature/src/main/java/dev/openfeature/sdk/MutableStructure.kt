package dev.openfeature.sdk

class MutableStructure(private var attributes: MutableMap<String, Value> = mutableMapOf()) : Structure {
    override fun keySet(): Set<String> {
        return attributes.keys
    }

    override fun getValue(key: String): Value? {
        return attributes[key]
    }

    override fun asMap(): MutableMap<String, Value> {
        return attributes
    }

    override fun asObjectMap(): Map<String, Any?> {
        return attributes.mapValues { convertValue(it.value) }
    }

    fun add(key: String, value: Value): MutableStructure {
        attributes[key] = value
        return this
    }

    private fun convertValue(value: Value): Any? {
        return when (value) {
            is Value.List -> value.list.map { t -> convertValue(t) }
            is Value.Structure -> value.structure.mapValues { t -> convertValue(t.value) }
            is Value.Null -> return null
            is Value.String -> value.asString()
            is Value.Boolean -> value.asBoolean()
            is Value.Integer -> value.asInteger()
            is Value.Date -> value.asDate()
            is Value.Double -> value.asDouble()
        }
    }

    override fun hashCode(): Int {
        return attributes.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MutableStructure

        if (attributes != other.attributes) return false

        return true
    }
}