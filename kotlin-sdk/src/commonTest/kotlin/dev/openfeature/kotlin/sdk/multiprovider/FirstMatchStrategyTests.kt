package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.RecordingBooleanProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirstMatchStrategyTests {

    @Test
    fun returnsFirstSuccessWithoutCallingNextProviders() {
        val strategy = FirstMatchStrategy()
        val first = RecordingBooleanProvider(
            name = "first",
            behavior = { ProviderEvaluation(true) }
        )
        val second = RecordingBooleanProvider(
            name = "second",
            behavior = { ProviderEvaluation(false) }
        )

        val result = strategy.evaluate(
            listOf(first, second),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(1, first.booleanEvalCalls)
        // Short-circuits after first provider
        assertEquals(0, second.booleanEvalCalls)
    }

    @Test
    fun skipsFlagNotFoundAndReturnsNextMatch() {
        val strategy = FirstMatchStrategy()
        val notFoundProvider = RecordingBooleanProvider(
            name = "not-found",
            behavior = { ProviderEvaluation(false, errorCode = ErrorCode.FLAG_NOT_FOUND) }
        )
        val matchingProvider = RecordingBooleanProvider(
            name = "match",
            behavior = { ProviderEvaluation(true) }
        )
        val neverCalled = RecordingBooleanProvider(
            name = "never",
            behavior = { ProviderEvaluation(false) }
        )

        val result = strategy.evaluate(
            listOf(notFoundProvider, matchingProvider, neverCalled),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        // First provider called but skipped
        assertEquals(1, notFoundProvider.booleanEvalCalls)
        // Second provider returned and short-circuited
        assertEquals(1, matchingProvider.booleanEvalCalls)
        assertEquals(0, neverCalled.booleanEvalCalls)
    }

    @Test
    fun treatsFlagNotFoundExceptionAsNotFoundAndContinues() {
        val strategy = FirstMatchStrategy()
        val throwsNotFound = RecordingBooleanProvider(
            name = "throws-not-found",
            behavior = { throw OpenFeatureError.FlagNotFoundError("flag") }
        )
        val matchingProvider = RecordingBooleanProvider(
            name = "match",
            behavior = { ProviderEvaluation(true) }
        )

        val result = strategy.evaluate(
            listOf(throwsNotFound, matchingProvider),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(1, throwsNotFound.booleanEvalCalls)
        assertEquals(1, matchingProvider.booleanEvalCalls)
    }

    @Test
    fun returnsErrorResultOtherThanNotFoundAndShortCircuits() {
        val strategy = FirstMatchStrategy()
        val errorProvider = RecordingBooleanProvider(
            name = "error",
            behavior = { ProviderEvaluation(false, errorCode = ErrorCode.GENERAL, errorMessage = "boom") }
        )
        val neverCalled = RecordingBooleanProvider(
            name = "never",
            behavior = { ProviderEvaluation(true) }
        )

        val result = strategy.evaluate(
            listOf(errorProvider, neverCalled),
            key = "flag",
            defaultValue = true,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(false, result.value)
        assertEquals(ErrorCode.GENERAL, result.errorCode)
        assertEquals(1, errorProvider.booleanEvalCalls)
        assertEquals(0, neverCalled.booleanEvalCalls)
    }

    @Test
    fun bubblesUpNonNotFoundExceptions() {
        val strategy = FirstMatchStrategy()
        val throwsGeneral = RecordingBooleanProvider(
            name = "throws-general",
            behavior = { throw OpenFeatureError.GeneralError("fail") }
        )

        assertFailsWith<OpenFeatureError.GeneralError> {
            strategy.evaluate(
                listOf(throwsGeneral),
                key = "flag",
                defaultValue = false,
                evaluationContext = null,
                flagEval = FeatureProvider::getBooleanEvaluation
            )
        }
        assertEquals(1, throwsGeneral.booleanEvalCalls)
    }

    @Test
    fun returnsDefaultWithNotFoundWhenNoProviderMatches() {
        val strategy = FirstMatchStrategy()
        val p1 = RecordingBooleanProvider(
            name = "p1",
            behavior = { ProviderEvaluation(false, errorCode = ErrorCode.FLAG_NOT_FOUND) }
        )
        val p2 = RecordingBooleanProvider(
            name = "p2",
            behavior = { throw OpenFeatureError.FlagNotFoundError("flag") }
        )

        val result = strategy.evaluate(
            listOf(p1, p2),
            key = "flag",
            defaultValue = true,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, result.errorCode)
        assertEquals(1, p1.booleanEvalCalls)
        assertEquals(1, p2.booleanEvalCalls)
    }
}