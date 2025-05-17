package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode

data class ProviderEvaluation<T>(
    val value: T,
    val variant: String? = null,
    val reason: String? = null,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val metadata: EvaluationMetadata = EvaluationMetadata.EMPTY
)