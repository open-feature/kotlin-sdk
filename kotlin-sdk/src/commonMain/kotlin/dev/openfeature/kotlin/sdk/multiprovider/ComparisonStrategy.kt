package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

class ComparisonStrategy(
    private val fallbackProvider: FeatureProvider? = null,
    private val onMismatch: ((Map<String, ProviderEvaluation<*>>) -> Unit)? = null
) : BaseEvaluationStrategy() {
    override fun <T> evaluate(
        providers: List<FeatureProvider>,
        key: String,
        defaultValue: T,
        evaluationContext: EvaluationContext?,
        flagEval: FlagEval<T>
    ): ProviderEvaluation<T> {
        val successfulEvaluations = mutableListOf<Pair<FeatureProvider, ProviderEvaluation<T>>>()
        val flagNotFoundEvaluations = mutableListOf<Pair<FeatureProvider, ProviderEvaluation<T>>>()
        val comparisonErrors = mutableListOf<ComparisonError>()

        providers.forEach { provider ->
            if (!shouldEvaluate(provider)) {
                return@forEach
            }

            try {
                val evaluation = provider.flagEval(key, defaultValue, evaluationContext)
                when (evaluation.errorCode) {
                    null -> successfulEvaluations += provider to evaluation
                    ErrorCode.FLAG_NOT_FOUND -> flagNotFoundEvaluations += provider to evaluation
                    else -> comparisonErrors += ComparisonError(
                        providerName = providerName(provider),
                        errorCode = evaluation.errorCode,
                        errorMessage = evaluation.errorMessage
                            ?: "Provider '${providerName(provider)}' returned ${evaluation.errorCode}"
                    )
                }
            } catch (error: OpenFeatureError.FlagNotFoundError) {
                flagNotFoundEvaluations += provider to ProviderEvaluation(
                    value = defaultValue,
                    reason = Reason.DEFAULT.toString(),
                    errorCode = ErrorCode.FLAG_NOT_FOUND,
                    errorMessage = error.message
                )
            } catch (error: OpenFeatureError) {
                comparisonErrors += ComparisonError(
                    providerName = providerName(provider),
                    errorCode = error.errorCode(),
                    errorMessage = error.message ?: "Provider '${providerName(provider)}' threw an error"
                )
            }
        }

        if (comparisonErrors.isNotEmpty()) {
            return ProviderEvaluation(
                value = defaultValue,
                reason = Reason.ERROR.toString(),
                errorCode = aggregateErrorCode(comparisonErrors),
                errorMessage = buildErrorMessage(comparisonErrors)
            )
        }

        if (successfulEvaluations.isEmpty()) {
            return ProviderEvaluation(
                value = defaultValue,
                reason = Reason.DEFAULT.toString(),
                errorCode = ErrorCode.FLAG_NOT_FOUND
            )
        }

        val firstValue = successfulEvaluations.first().second.value
        val providersAgree = successfulEvaluations.all { it.second.value == firstValue }
        if (providersAgree) {
            return successfulEvaluations.first().second
        }

        val mismatchEvaluations = successfulEvaluations.associate { (provider, evaluation) ->
            providerName(provider) to (evaluation as ProviderEvaluation<*>)
        }
        onMismatch?.invoke(mismatchEvaluations)

        val fallbackEvaluation = resolveFallbackEvaluation(successfulEvaluations, flagNotFoundEvaluations)
        val fallbackProviderName = providerName(fallbackEvaluation.first)
        return fallbackEvaluation.second.copy(
            reason = Reason.ERROR.toString(),
            errorCode = ErrorCode.GENERAL,
            errorMessage = buildMismatchMessage(
                mismatchEvaluations = mismatchEvaluations,
                fallbackProviderName = fallbackProviderName
            )
        )
    }

    private fun <T> resolveFallbackEvaluation(
        successfulEvaluations: List<Pair<FeatureProvider, ProviderEvaluation<T>>>,
        flagNotFoundEvaluations: List<Pair<FeatureProvider, ProviderEvaluation<T>>>
    ): Pair<FeatureProvider, ProviderEvaluation<T>> {
        val configuredFallbackProvider = fallbackProvider ?: return successfulEvaluations.first()
        val allEvaluations = successfulEvaluations + flagNotFoundEvaluations
        return allEvaluations.firstOrNull { (provider, _) ->
            unwrapProvider(provider) === unwrapProvider(configuredFallbackProvider)
        } ?: successfulEvaluations.first()
    }

    private fun aggregateErrorCode(comparisonErrors: List<ComparisonError>): ErrorCode {
        val distinctErrorCodes = comparisonErrors.map { it.errorCode }.distinct()
        return distinctErrorCodes.singleOrNull() ?: ErrorCode.GENERAL
    }

    private fun buildErrorMessage(comparisonErrors: List<ComparisonError>): String {
        return comparisonErrors.joinToString(
            prefix = "ComparisonStrategy encountered one or more provider errors: ",
            separator = "\n"
        ) { comparisonError ->
            "${comparisonError.providerName}: ${comparisonError.errorMessage}"
        }
    }

    private fun buildMismatchMessage(
        mismatchEvaluations: Map<String, ProviderEvaluation<*>>,
        fallbackProviderName: String
    ): String {
        val mismatchValues = mismatchEvaluations.entries.joinToString { (providerName, evaluation) ->
            "$providerName=${evaluation.value}"
        }
        return buildString {
            append("ComparisonStrategy detected mismatched evaluations: ")
            append(mismatchValues)
            append(". Using fallback provider '")
            append(fallbackProviderName)
            append("'.")
        }
    }

    private data class ComparisonError(
        val providerName: String,
        val errorCode: ErrorCode,
        val errorMessage: String
    )
}