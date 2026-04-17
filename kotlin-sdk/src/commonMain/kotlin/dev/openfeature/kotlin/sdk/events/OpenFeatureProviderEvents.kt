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
     */
    data class ProviderReady(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * The provider signaled an error.
     */
    data class ProviderError(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    data class ProviderConfigurationChanged(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()

    /**
     * The provider's cached state is no longer valid and may not be up-to-date with the source of truth.
     */
    data class ProviderStale(
        override val eventDetails: EventDetails? = null
    ) : OpenFeatureProviderEvents()
}

internal fun OpenFeatureProviderEvents.ProviderError.toOpenFeatureStatusError(): OpenFeatureStatus {
    val details = eventDetails
    val code = details?.errorCode ?: return OpenFeatureStatus.Error(
        OpenFeatureError.GeneralError(details?.message ?: "Unspecified error")
    )
    val openFeatureError = OpenFeatureError.fromMessageAndErrorCode(
        errorMessage = details.message ?: "Provider did not supply an error message",
        errorCode = code
    )
    return if (code == ErrorCode.PROVIDER_FATAL) {
        OpenFeatureStatus.Fatal(openFeatureError)
    } else {
        OpenFeatureStatus.Error(openFeatureError)
    }
}