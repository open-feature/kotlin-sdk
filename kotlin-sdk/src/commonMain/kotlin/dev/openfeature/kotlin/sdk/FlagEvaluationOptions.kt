package dev.openfeature.kotlin.sdk

/**
 * @param evaluationContext Optional context merged with the API [OpenFeatureAPI.getEvaluationContext]
 * for this evaluation only. Does not update global context or invoke [FeatureProvider.onContextSet].
 * See [mergeEvaluationContexts] for merge rules.
 */
data class FlagEvaluationOptions(
    val hooks: List<Hook<*>> = listOf(),
    val hookHints: Map<String, Any> = mapOf(),
    val evaluationContext: EvaluationContext? = null
)