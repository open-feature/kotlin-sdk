package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

/**
 * A [MultiProvider.Strategy] that returns the first result returned by a [FeatureProvider].
 *
 * It skips providers that indicate they had no value due to [ErrorCode.FLAG_NOT_FOUND].
 * In all other cases, it uses the value returned by the provider.
 *
 * If any provider returns an error result other than [ErrorCode.FLAG_NOT_FOUND], the whole evaluation
 * returns the provider's error.
 */
class FirstMatchStrategy : MultiProvider.Strategy {
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
            } catch (_: OpenFeatureError.FlagNotFoundError) {
                // Handle FLAG_NOT_FOUND exception - continue to next provider
                continue
            } catch (error: OpenFeatureError) {
                return ProviderEvaluation(
                    defaultValue,
                    reason = Reason.ERROR.toString(),
                    errorCode = error.errorCode(),
                    errorMessage = error.message
                )
            }
        }

        // No provider knew about the flag, return default value with DEFAULT reason
        return ProviderEvaluation(
            defaultValue,
            reason = Reason.DEFAULT.toString(),
            errorCode = ErrorCode.FLAG_NOT_FOUND
        )
    }
}