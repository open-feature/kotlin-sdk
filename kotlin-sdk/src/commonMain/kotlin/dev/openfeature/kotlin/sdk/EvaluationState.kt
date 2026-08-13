package dev.openfeature.kotlin.sdk

/**
 * Atomic snapshot of the provider and evaluation context used for flag evaluation and tracking.
 */
internal data class EvaluationState(
    val provider: FeatureProvider,
    val context: EvaluationContext?
)