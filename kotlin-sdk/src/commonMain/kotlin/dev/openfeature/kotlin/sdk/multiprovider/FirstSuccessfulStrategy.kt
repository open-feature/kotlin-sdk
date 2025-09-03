package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

/**
 * A [MultiProvider.Strategy] similar to the [FirstMatchStrategy], except that errors from evaluated
 * providers do not halt execution.
 *
 * If no provider responds successfully, it returns an error result.
 */
class FirstSuccessfulStrategy : MultiProvider.Strategy {
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
        return ProviderEvaluation(
            value = defaultValue,
            reason = Reason.DEFAULT.toString(),
            errorCode = ErrorCode.FLAG_NOT_FOUND,
            errorMessage = "No provider returned a successful evaluation for the requested flag."
        )
    }
}