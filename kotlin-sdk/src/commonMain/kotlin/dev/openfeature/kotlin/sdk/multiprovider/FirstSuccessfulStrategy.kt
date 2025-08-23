package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

/**
 * Similar to "First Match", except that errors from evaluated providers do not halt execution.
 * Instead, it will return the first successful result from a provider.
 *
 * If no provider successfully responds, it will throw an error result.
 */
class FirstSuccessfulStrategy : Strategy {
    /**
     * Evaluates providers in sequence until finding one that returns a successful result.
     *
     * @param providers List of providers to evaluate in order
     * @param key The feature flag key to evaluate
     * @param defaultValue Value to use in provider evaluations
     * @param evaluationContext Optional context for evaluation
     * @param flagEval The specific evaluation method to call on each provider
     * @return ProviderEvaluation with the first successful result
     * @throws OpenFeatureError.GeneralError if no provider returns a successful evaluation
     */
    override fun <T> evaluate(
        providers: List<FeatureProvider>,
        key: String,
        defaultValue: T,
        evaluationContext: EvaluationContext?,
        flagEval: FlagEval<T>
    ): ProviderEvaluation<T> {
        // Iterate through each provider in the provided order
        for (provider in providers) {
            try {
                // Call the flag evaluation method on the current provider
                val eval = provider.flagEval(key, defaultValue, evaluationContext)

                // If the provider returned a successful result (no error),
                // return this result immediately
                if (eval.errorCode == null) {
                    return eval
                }
                // Continue to next provider if this one had an error
            } catch (_: OpenFeatureError) {
                // Handle any OpenFeature exceptions - continue to next provider
                // FirstSuccessful strategy skips errors and continues
                continue
            }
        }

        // No provider returned a successful result, throw an error
        // This indicates that all providers either failed or had errors
        throw OpenFeatureError.GeneralError("No provider returned a successful evaluation for the requested flag.")
    }
}