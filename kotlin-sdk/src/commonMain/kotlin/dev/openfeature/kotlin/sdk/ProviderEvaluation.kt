package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.ErrorCode

data class ProviderEvaluation<T>(
    val value: T,
    val variant: String? = null,
    val reason: String? = null,
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    val metadata: EvaluationMetadata = EvaluationMetadata.EMPTY
)