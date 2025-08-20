package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

/**
 * Return the first result returned by a provider. Skip providers that indicate they had no value due to FLAG_NOT_FOUND.
 * In all other cases, use the value returned by the provider. If any provider returns an error result other than
 * FLAG_NOT_FOUND, the whole evaluation should error and "bubble up" the individual provider's error in the result.
 *
 * As soon as a value is returned by a provider, the rest of the operation should short-circuit and not call the
 * rest of the providers.
 */
class FirstMatchStrategy : Strategy {
    /**
     * Evaluates providers in sequence until finding one that has knowledge of the flag.
     *
     * @param providers List of providers to evaluate in order
     * @param key The feature flag key to look up
     * @param defaultValue Value to return if no provider knows about the flag
     * @param evaluationContext Optional context for evaluation
     * @param flagEval The specific evaluation method to call on each provider
     * @return ProviderEvaluation with the first match or default value
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

                // If the provider knows about this flag (any result except FLAG_NOT_FOUND),
                // return this result immediately, even if it's an error
                if (eval.errorCode != ErrorCode.FLAG_NOT_FOUND) {
                    return eval
                }
                // Continue to next provider if error is FLAG_NOT_FOUND
            } catch (_: OpenFeatureError.FlagNotFoundError) {
                // Handle FLAG_NOT_FOUND exception - continue to next provider
                continue
            }
            // We don't catch any other exception, but rather, bubble up the exceptions
        }

        // No provider knew about the flag, return default value with DEFAULT reason
        return ProviderEvaluation(defaultValue, errorCode = ErrorCode.FLAG_NOT_FOUND)
    }
}