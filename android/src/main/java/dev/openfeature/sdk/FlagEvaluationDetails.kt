package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode

data class FlagEvaluationDetails<T>(
    val flagKey: String,
    val value: T,
    val variant: String? = null,
    val reason: String? = null,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null
) {
    companion object
}

fun <T> FlagEvaluationDetails.Companion.from(
    providerEval: ProviderEvaluation<T>,
    flagKey: String
): FlagEvaluationDetails<T> {
    return FlagEvaluationDetails(
        flagKey,
        providerEval.value,
        providerEval.variant,
        providerEval.reason,
        providerEval.errorCode,
        providerEval.errorMessage
    )
}