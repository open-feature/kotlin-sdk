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
     * The provider is in the process of reconciling within a context change.
     */
    data class ProviderReconciling(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()
}

/**
 * Derives the [OpenFeatureStatus] implied by this event for status aggregation and tracking, if any.
 *
 * Returns `null` when the event does not represent a status transition from the SDK's perspective.
 */
internal fun OpenFeatureProviderEvents.toOpenFeatureStatus(): OpenFeatureStatus? = when (this) {
    is OpenFeatureProviderEvents.ProviderReady -> OpenFeatureStatus.Ready
    is OpenFeatureProviderEvents.ProviderReconciling -> OpenFeatureStatus.Reconciling
    is OpenFeatureProviderEvents.ProviderStale -> OpenFeatureStatus.Stale
    is OpenFeatureProviderEvents.ProviderConfigurationChanged -> null
    is OpenFeatureProviderEvents.ProviderError -> toOpenFeatureStatusError()
    else -> null
}

internal fun OpenFeatureProviderEvents.ProviderError.toOpenFeatureStatusError(): OpenFeatureStatus {
    val code = eventDetails?.errorCode ?: return OpenFeatureStatus.Error(
        OpenFeatureError.GeneralError(eventDetails?.message ?: "Unspecified error")
    )
    if (code == ErrorCode.PROVIDER_NOT_READY) return OpenFeatureStatus.NotReady
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