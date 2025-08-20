package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation

/**
 * Type alias for a function that evaluates a feature flag using a FeatureProvider.
 * This represents an extension function on FeatureProvider that takes:
 * - key: The feature flag key to evaluate
 * - defaultValue: The default value to return if evaluation fails
 * - evaluationContext: Optional context for the evaluation
 * Returns a ProviderEvaluation containing the result
 */
typealias FlagEval<T> = FeatureProvider.(key: String, defaultValue: T, evaluationContext: EvaluationContext?) -> ProviderEvaluation<T>

/**
 * Strategy interface defines how multiple feature providers should be evaluated
 * to determine the final result for a feature flag evaluation.
 * Different strategies can implement different logic for combining or selecting
 * results from multiple providers.
 */
interface Strategy {
    /**
     * Evaluates a feature flag across multiple providers using the strategy's logic.
     * 
     * @param providers List of FeatureProvider instances to evaluate against
     * @param key The feature flag key to evaluate
     * @param defaultValue The default value to use if evaluation fails or no providers match
     * @param evaluationContext Optional context containing additional data for evaluation
     * @param flagEval Function reference to the specific evaluation method to call on each provider
     * @return ProviderEvaluation<T> containing the final evaluation result
     */
    fun <T> evaluate(
        providers: List<FeatureProvider>,
        key: String,
        defaultValue: T,
        evaluationContext: EvaluationContext?,
        flagEval: FlagEval<T>,
    ): ProviderEvaluation<T>
}