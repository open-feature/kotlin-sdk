package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode

data class FlagEvaluationDetails<T>(
    val flagKey: String,
    override val value: T,
    override val variant: String? = null,
    override val reason: String? = null,
    override val errorCode: ErrorCode? = null,
    override val errorMessage: String? = null
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