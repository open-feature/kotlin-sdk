package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatusError
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
    fun providerErrorEventDetailsMapToInvalidContextError() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = "message",
                errorCode = ErrorCode.INVALID_CONTEXT
            )
        )

        val status = evt.toOpenFeatureStatusError()
        val errorStatus = assertIs<OpenFeatureStatus.Error>(status)
        val err = assertIs<OpenFeatureError.InvalidContextError>(errorStatus.error)
        assertEquals("message", err.message)
    }

    @Test
    fun providerErrorMapToUnspecifiedError() {
        val evt = OpenFeatureProviderEvents.ProviderError()

        val status = evt.toOpenFeatureStatusError()
        val errorStatus = assertIs<OpenFeatureStatus.Error>(status)
        val err = assertIs<OpenFeatureError.GeneralError>(errorStatus.error)
        assertEquals("Unspecified error", err.message)
    }

    @Test
    fun providerErrorEventDetailsWithMessageAndNullErrorCodeMapToGeneralError() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = "test",
                errorCode = null
            )
        )

        val status = evt.toOpenFeatureStatusError()
        val errorStatus = assertIs<OpenFeatureStatus.Error>(status)
        val err = assertIs<OpenFeatureError.GeneralError>(errorStatus.error)
        assertEquals("test", err.message)
    }

    @Test
    fun readyEventMapsToReadyStatus() {
        assertEquals(
            OpenFeatureStatus.Ready,
            OpenFeatureProviderEvents.ProviderReady().toOpenFeatureStatus()
        )
    }

    @Test
    fun staleEventMapsToStaleStatus() {
        assertEquals(
            OpenFeatureStatus.Stale,
            OpenFeatureProviderEvents.ProviderStale().toOpenFeatureStatus()
        )
    }

    @Test
    fun reconcilingEventMapsToReconcilingStatus() {
        assertEquals(
            OpenFeatureStatus.Reconciling,
            OpenFeatureProviderEvents.ProviderReconciling().toOpenFeatureStatus()
        )
    }

    @Test
    fun contextChangedEventMapsToReadyStatus() {
        assertEquals(
            OpenFeatureStatus.Ready,
            OpenFeatureProviderEvents.ProviderContextChanged().toOpenFeatureStatus()
        )
    }

    @Test
    fun configurationChangedEventMapsToNoStatusTransition() {
        assertNull(OpenFeatureProviderEvents.ProviderConfigurationChanged().toOpenFeatureStatus())
    }

    @Test
    fun errorEventMapsThroughToErrorStatus() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = "flag missing",
                errorCode = ErrorCode.FLAG_NOT_FOUND
            )
        )

        val error = assertIs<OpenFeatureStatus.Error>(evt.toOpenFeatureStatus())
        assertIs<OpenFeatureError.FlagNotFoundError>(error.error)
    }

    @Test
    fun errorEventWithFatalCodeMapsThroughToFatalStatus() {
        val evt = OpenFeatureProviderEvents.ProviderError(
            OpenFeatureProviderEvents.EventDetails(
                message = "unrecoverable",
                errorCode = ErrorCode.PROVIDER_FATAL
            )
        )

        assertIs<OpenFeatureStatus.Fatal>(evt.toOpenFeatureStatus())
    }

    @Test
    fun eventDetailsCarryProviderName() {
        val details = OpenFeatureProviderEvents.EventDetails(providerName = "my-provider")
        assertEquals("my-provider", details.providerName)
    }
}