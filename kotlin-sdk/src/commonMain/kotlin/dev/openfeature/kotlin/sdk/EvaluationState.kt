package dev.openfeature.kotlin.sdk

/**
 * Atomic snapshot of the provider, evaluation context and hooks used for flag evaluation and
 * tracking, so a single evaluation cannot observe half of a concurrent change.
 */
internal data class EvaluationState(
    val provider: FeatureProvider,
    val context: EvaluationContext?,
    val hooks: List<Hook<*>> = listOf()
)