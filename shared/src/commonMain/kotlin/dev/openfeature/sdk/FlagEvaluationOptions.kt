package dev.openfeature.sdk

data class FlagEvaluationOptions(
    val hooks: List<Hook<*>> = listOf(),
    val hookHints: Map<String, Any> = mapOf()
)