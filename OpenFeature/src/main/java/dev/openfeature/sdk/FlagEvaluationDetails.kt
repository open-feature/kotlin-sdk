package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode

data class FlagEvaluationDetails<T>(
    val flagKey: String,
    override var value: T,
    override var variant: String? = null,
    override var reason: String? = null,
    override var errorCode: ErrorCode? = null,
    override var errorMessage: String? = null
) : BaseEvaluation<T> {
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