package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatusError
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EventDetailsTests {

    @Test
    fun providerErrorEventDetailsMapToFatal() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = "message",
                errorCode = ErrorCode.PROVIDER_FATAL
            )
        )

        val status = evt.toOpenFeatureStatusError()
        val fatal = assertIs<OpenFeatureStatus.Fatal>(status)
        val err = assertIs<OpenFeatureError.ProviderFatalError>(fatal.error)
        assertEquals("message", err.message)
    }

    @Test
    fun providerErrorEventDetailsMapToError() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = "flag missing",
                errorCode = ErrorCode.FLAG_NOT_FOUND
            )
        )

        val status = evt.toOpenFeatureStatusError()
        val error = assertIs<OpenFeatureStatus.Error>(status)
        assertIs<OpenFeatureError.FlagNotFoundError>(error.error)
        assertEquals("flag missing", error.error.message)
    }

    @Test
    fun providerErrorMapToFatal() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            error = OpenFeatureError.ProviderFatalError("message")
        )

        val status = evt.toOpenFeatureStatusError()
        val fatal = assertIs<OpenFeatureStatus.Fatal>(status)
        val err = assertIs<OpenFeatureError.ProviderFatalError>(fatal.error)
        assertEquals("message", err.message)
    }

    @Test
    fun providerErrorMapToError() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            error = OpenFeatureError.InvalidContextError("message")
        )

        val status = evt.toOpenFeatureStatusError()
        val fatal = assertIs<OpenFeatureStatus.Error>(status)
        val err = assertIs<OpenFeatureError.InvalidContextError>(fatal.error)
        assertEquals("message", err.message)
    }

    @Test
    fun providerErrorMapToUnspecifiedError() {
        val evt = OpenFeatureProviderEvents.ProviderError()

        val status = evt.toOpenFeatureStatusError()
        val fatal = assertIs<OpenFeatureStatus.Error>(status)
        assertIs<OpenFeatureError.GeneralError>(fatal.error)
    }
}