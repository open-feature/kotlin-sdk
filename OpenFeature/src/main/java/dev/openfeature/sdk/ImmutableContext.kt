package dev.openfeature.sdk

class ImmutableContext
(private var targetingKey: String = "", attributes: Map<String, Value> = mapOf()) : EvaluationContext {
    private var structure: ImmutableStructure = ImmutableStructure(attributes)
    override fun getTargetingKey(): String {
        return targetingKey
    }

    override fun setTargetingKey(targetingKey: String) {
        this.targetingKey = targetingKey
    }

    override fun keySet(): Set<String> {
        return structure.keySet()
    }

    override fun getValue(key: String): Value? {
        return structure.getValue(key)
    }

    override fun asMap(): Map<String, Value> {
        return structure.asMap()
    }

    override fun asObjectMap(): Map<String, Any?> {
        return structure.asObjectMap()
    }

    override fun hashCode(): Int {
        var result = targetingKey.hashCode()
        result = 31 * result + structure.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImmutableContext

        if (targetingKey != other.targetingKey) return false
        if (structure != other.structure) return false

        return true
    }
}