package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.RecordingBooleanProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComparisonStrategyTests {
    @Test
    fun ignoresProvidersThatAreNotReadyOrFatal() {
        val first = RecordingBooleanProvider("first") { ProviderEvaluation(true) }
        val second = RecordingBooleanProvider("second") { ProviderEvaluation(true) }
        val notReady = RecordingBooleanProvider("notReady") { ProviderEvaluation(false) }
        val fatal = RecordingBooleanProvider("fatal") { ProviderEvaluation(false) }
        val strategy = ComparisonStrategy(fallbackProvider = first)

        val result = strategy.evaluate(
            listOf(
                MultiProvider.ProviderWithStatus(notReady, OpenFeatureStatus.NotReady),
                MultiProvider.ProviderWithStatus(fatal, OpenFeatureStatus.Fatal(OpenFeatureError.ProviderFatalError())),
                first,
                second
            ),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(0, notReady.booleanEvalCalls)
        assertEquals(0, fatal.booleanEvalCalls)
        assertEquals(1, first.booleanEvalCalls)
        assertEquals(1, second.booleanEvalCalls)
    }

    @Test
    fun returnsFallbackResultAndInvokesMismatchCallback() {
        val first = RecordingBooleanProvider("first") {
            ProviderEvaluation(true, reason = Reason.TARGETING_MATCH.toString())
        }
        val second = RecordingBooleanProvider("second") {
            ProviderEvaluation(false, reason = Reason.STATIC.toString())
        }
        var mismatchEvaluations: Map<String, ProviderEvaluation<*>>? = null
        val strategy = ComparisonStrategy(
            fallbackProvider = second,
            onMismatch = { mismatchEvaluations = it }
        )

        val result = strategy.evaluate(
            listOf(first, second),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(false, result.value)
        assertEquals(ErrorCode.GENERAL, result.errorCode)
        assertEquals(Reason.ERROR.toString(), result.reason)
        assertTrue(result.errorMessage.orEmpty().contains("first=true"))
        assertTrue(result.errorMessage.orEmpty().contains("second=false"))
        assertTrue(result.errorMessage.orEmpty().contains("fallback provider 'second'"))
        val recordedMismatchEvaluations = assertNotNull(mismatchEvaluations)
        assertEquals(setOf("first", "second"), recordedMismatchEvaluations.keys)
    }

    @Test
    fun returnsDefaultWhenAllProvidersReturnFlagNotFound() {
        val first = RecordingBooleanProvider("first") {
            ProviderEvaluation(false, errorCode = ErrorCode.FLAG_NOT_FOUND)
        }
        val second = RecordingBooleanProvider("second") {
            throw OpenFeatureError.FlagNotFoundError("flag")
        }
        val strategy = ComparisonStrategy(fallbackProvider = first)

        val result = strategy.evaluate(
            listOf(first, second),
            key = "flag",
            defaultValue = true,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(true, result.value)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, result.errorCode)
        assertEquals(1, first.booleanEvalCalls)
        assertEquals(1, second.booleanEvalCalls)
    }

    @Test
    fun aggregatesProviderErrorsIntoSingleErrorResult() {
        val first = RecordingBooleanProvider("first") {
            ProviderEvaluation(false, errorCode = ErrorCode.GENERAL, errorMessage = "bad result")
        }
        val second = RecordingBooleanProvider("second") {
            throw OpenFeatureError.ProviderFatalError("fatal")
        }
        val third = RecordingBooleanProvider("third") {
            ProviderEvaluation(true)
        }
        val strategy = ComparisonStrategy(fallbackProvider = first)

        val result = strategy.evaluate(
            listOf(first, second, third),
            key = "flag",
            defaultValue = false,
            evaluationContext = null,
            flagEval = FeatureProvider::getBooleanEvaluation
        )

        assertEquals(false, result.value)
        assertEquals(ErrorCode.GENERAL, result.errorCode)
        assertEquals(Reason.ERROR.toString(), result.reason)
        assertTrue(result.errorMessage.orEmpty().contains("first: bad result"))
        assertTrue(result.errorMessage.orEmpty().contains("second: fatal"))
        assertEquals(1, first.booleanEvalCalls)
        assertEquals(1, second.booleanEvalCalls)
        assertEquals(1, third.booleanEvalCalls)
    }
}
