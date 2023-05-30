package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode

data class ProviderEvaluation<T>(
    var value: T,
    var variant: String? = null,
    var reason: String? = null,
    var errorCode: ErrorCode? = null,
    var errorMessage: String? = null
)
