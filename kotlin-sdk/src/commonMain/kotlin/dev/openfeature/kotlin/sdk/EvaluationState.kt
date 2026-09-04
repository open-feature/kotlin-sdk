package dev.openfeature.kotlin.sdk

/**
 * Atomic snapshot of the provider, evaluation context and hooks used for flag evaluation and
 * tracking.
 */
internal data class EvaluationState(
    val provider: FeatureProvider,
    val context: EvaluationContext?,
    val hooks: List<Hook<*>>
)