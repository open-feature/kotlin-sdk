package dev.openfeature.sdk

data class FlagEvaluationOptions(
    var hooks: List<Hook<*>> = listOf(),
    var hookHints: Map<String, Any> = mapOf()
)