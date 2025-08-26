package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.RecordingBooleanProvider
import kotlin.test.Test
import kotlin.test.assertEquals

class FirstSuccessfulStrategyTests {

    @Test
    fun returnsFirstSuccessIgnoringPriorErrors() {
        val strategy = FirstSuccessfulStrategy()
        val error1 = RecordingBooleanProvider("e1") {
            throw OpenFeatureError.GeneralError("boom1")
        }
        val error2 = RecordingBooleanProvider("e2") {
            // Simulate provider returning error result (not success)
            dev.openfeature.kotlin.sdk.ProviderEvaluation(false, errorCode = ErrorCode.GENERAL)
        }
        val success = RecordingBooleanProvider("ok") {
            dev.openfeature.kotlin.sdk.ProviderEvaluation(true)
        }
        val never = RecordingBooleanProvider("never") {
            dev.openfeature.kotlin.sdk.ProviderEvaluation(false)
        }

        val result = strategy.evaluate(
            listOf(error1, error2, success, never),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(1, error1.booleanEvalCalls)
        assertEquals(1, error2.booleanEvalCalls)
        assertEquals(1, success.booleanEvalCalls)
        assertEquals(0, never.booleanEvalCalls)
    }

    @Test
    fun skipsFlagNotFoundErrorAndResultUntilSuccess() {
        val strategy = FirstSuccessfulStrategy()
        val notFoundThrow = RecordingBooleanProvider("nf-throw") {
            throw OpenFeatureError.FlagNotFoundError("flag")
        }
        val notFoundResult = RecordingBooleanProvider("nf-result") {
            dev.openfeature.kotlin.sdk.ProviderEvaluation(false, errorCode = ErrorCode.FLAG_NOT_FOUND)
        }
        val success = RecordingBooleanProvider("ok") {
            dev.openfeature.kotlin.sdk.ProviderEvaluation(true)
        }

        val result = strategy.evaluate(
            listOf(notFoundThrow, notFoundResult, success),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(1, notFoundThrow.booleanEvalCalls)
        assertEquals(1, notFoundResult.booleanEvalCalls)
        assertEquals(1, success.booleanEvalCalls)
    }

    @Test
    fun returnsErrorWhenNoProviderReturnsSuccess() {
        val strategy = FirstSuccessfulStrategy()
        val error1 = RecordingBooleanProvider("e1") {
            throw OpenFeatureError.GeneralError("boom1")
        }
        val error2 = RecordingBooleanProvider("e2") {
            dev.openfeature.kotlin.sdk.ProviderEvaluation(false, errorCode = ErrorCode.GENERAL)
        }
        val notFound = RecordingBooleanProvider("nf") {
            dev.openfeature.kotlin.sdk.ProviderEvaluation(false, errorCode = ErrorCode.FLAG_NOT_FOUND)
        }
        val result = strategy.evaluate(
            listOf(error1, error2, notFound),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )
        assertEquals(false, result.value)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, result.errorCode)
        assertEquals(1, error1.booleanEvalCalls)
        assertEquals(1, error2.booleanEvalCalls)
        assertEquals(1, notFound.booleanEvalCalls)
    }
}