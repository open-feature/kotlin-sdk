package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.ErrorCode

interface BaseEvaluation<T> {
    val value: T
    val variant: String?
    val reason: String?
    val errorCode: ErrorCode?
    val errorMessage: String?
}