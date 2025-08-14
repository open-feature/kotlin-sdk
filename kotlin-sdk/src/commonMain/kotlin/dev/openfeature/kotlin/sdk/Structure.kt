package dev.openfeature.kotlin.sdk

interface Structure {
    fun keySet(): Set<String>
    fun getValue(key: String): Value?
    fun asMap(): Map<String, Value>
    fun asObjectMap(): Map<String, Any?>

    // Make sure these are implemented for correct object comparisons
    override fun hashCode(): Int
    override fun equals(other: Any?): Boolean
}