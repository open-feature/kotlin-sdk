package dev.openfeature.sdk

interface EvaluationContext : Structure {
    fun getTargetingKey(): String
    fun withTargetingKey(targetingKey: String): EvaluationContext

    // Make sure these are implemented for correct object comparisons
    override fun hashCode(): Int
    override fun equals(other: Any?): Boolean
}