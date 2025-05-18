package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode

data class FlagEvaluationDetails<T>(
    val flagKey: String,
    val value: T,
    val variant: String? = null,
    val reason: String? = null,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val metadata: EvaluationMetadata = EvaluationMetadata.EMPTY
) {
    companion object
}

fun <T> FlagEvaluationDetails.Companion.from(
    providerEval: ProviderEvaluation<T>,
    flagKey: String
): FlagEvaluationDetails<T> {
    return FlagEvaluationDetails(
        flagKey = flagKey,
        value = providerEval.value,
        variant = providerEval.variant,
        reason = providerEval.reason,
        errorCode = providerEval.errorCode,
        errorMessage = providerEval.errorMessage,
        metadata = providerEval.metadata
    )
}