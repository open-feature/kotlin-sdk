package dev.openfeature.kotlin.sdk.events

import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

sealed class OpenFeatureProviderEvents {
    data class EventDetails(
        val flagsChanged: Set<String> = emptySet(),
        val message: String? = null,
        val errorCode: ErrorCode? = null,
        val eventMetadata: Map<String, Any> = emptyMap()
    )

    abstract val eventDetails: EventDetails?

    /**
     * The provider is ready to perform flag evaluations.
     * [eventDetails] may supply [EventDetails.flagsChanged], [EventDetails.message], [EventDetails.errorCode], and [EventDetails.eventMetadata] as applicable.
     */
    data class ProviderReady(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * The provider signaled an error.
     * [eventDetails] may supply [EventDetails.flagsChanged], [EventDetails.message], [EventDetails.errorCode], and [EventDetails.eventMetadata] as applicable.
     */
    data class ProviderError(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * Configuration or flag definitions changed.
     * [eventDetails] may supply [EventDetails.flagsChanged], [EventDetails.message], [EventDetails.errorCode], and [EventDetails.eventMetadata] as applicable.
     */
    data class ProviderConfigurationChanged(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * The provider's cached state is no longer valid and may not be up-to-date with the source of truth.
     * [eventDetails] may supply [EventDetails.flagsChanged], [EventDetails.message], [EventDetails.errorCode], and [EventDetails.eventMetadata] as applicable.
     */
    data class ProviderStale(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * The provider started reconciling its state with a new [dev.openfeature.kotlin.sdk.EvaluationContext].
     * [eventDetails] may supply [EventDetails.flagsChanged], [EventDetails.message], [EventDetails.errorCode], and [EventDetails.eventMetadata] as applicable.
     */
    data class ProviderReconciling(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * The provider finished reconciling its state with a new [dev.openfeature.kotlin.sdk.EvaluationContext].
     * [eventDetails] may supply [EventDetails.flagsChanged], [EventDetails.message], [EventDetails.errorCode], and [EventDetails.eventMetadata] as applicable.
     */
    data class ProviderContextChanged(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()
}

internal fun OpenFeatureProviderEvents.ProviderError.toOpenFeatureStatusError(): OpenFeatureStatus {
    val code = eventDetails?.errorCode ?: return OpenFeatureStatus.Error(
        OpenFeatureError.GeneralError(eventDetails?.message ?: "Unspecified error")
    )
    val openFeatureError = OpenFeatureError.fromMessageAndErrorCode(
        errorMessage = eventDetails.message ?: "Provider did not supply an error message",
        errorCode = code
    )
    return if (code == ErrorCode.PROVIDER_FATAL) {
        OpenFeatureStatus.Fatal(openFeatureError)
    } else {
        OpenFeatureStatus.Error(openFeatureError)
    }
}

/**
 * Status implied by this event, per the event/status association table in the specification.
 *
 * Returns null for [OpenFeatureProviderEvents.ProviderConfigurationChanged], which carries no status.
 */
internal fun OpenFeatureProviderEvents.toOpenFeatureStatus(): OpenFeatureStatus? = when (this) {
    is OpenFeatureProviderEvents.ProviderReady -> OpenFeatureStatus.Ready
    is OpenFeatureProviderEvents.ProviderStale -> OpenFeatureStatus.Stale
    is OpenFeatureProviderEvents.ProviderError -> toOpenFeatureStatusError()
    is OpenFeatureProviderEvents.ProviderReconciling -> OpenFeatureStatus.Reconciling
    is OpenFeatureProviderEvents.ProviderContextChanged -> OpenFeatureStatus.Ready
    is OpenFeatureProviderEvents.ProviderConfigurationChanged -> null
}

/**
 * Event representing this status, so a subscriber attaching once the provider is already in a given
 * state is told about it immediately.
 *
 * Returns null for [OpenFeatureStatus.NotReady], which has no corresponding event type.
 */
internal fun OpenFeatureStatus.toCurrentStateEvent(): OpenFeatureProviderEvents? = when (this) {
    is OpenFeatureStatus.NotReady -> null
    is OpenFeatureStatus.Ready -> OpenFeatureProviderEvents.ProviderReady()
    is OpenFeatureStatus.Stale -> OpenFeatureProviderEvents.ProviderStale()
    is OpenFeatureStatus.Reconciling -> OpenFeatureProviderEvents.ProviderReconciling()
    is OpenFeatureStatus.Error -> OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(message = error.message, errorCode = error.errorCode())
    )
    is OpenFeatureStatus.Fatal -> OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(
            message = error.message,
            errorCode = ErrorCode.PROVIDER_FATAL
        )
    )
}