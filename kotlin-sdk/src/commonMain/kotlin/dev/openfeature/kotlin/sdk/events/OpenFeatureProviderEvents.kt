package dev.openfeature.kotlin.sdk.events

import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.ProviderFatalError

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
        override val eventDetails: EventDetails? = null,
        @Deprecated("Please use eventDetails instead.") val error: OpenFeatureError? = null
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

    @Deprecated("Use ProviderError instead", ReplaceWith("ProviderError"))
    data object ProviderNotReady : OpenFeatureProviderEvents() {
        override val eventDetails = null
    }
}

internal fun OpenFeatureProviderEvents.ProviderError.toOpenFeatureStatusError(): OpenFeatureStatus {
    return when {
        eventDetails?.errorCode != null -> {
            val openFeatureError = OpenFeatureError.fromMessageAndErrorCode(
                errorMessage = eventDetails.message ?: "Provider did not supply an error message",
                errorCode = eventDetails.errorCode
            )
            if (eventDetails.errorCode == ErrorCode.PROVIDER_FATAL) {
                OpenFeatureStatus.Fatal(openFeatureError)
            } else {
                OpenFeatureStatus.Error(openFeatureError)
            }
        }

        error != null -> { // Deprecated implementation
            if (error is ProviderFatalError) {
                OpenFeatureStatus.Fatal(error)
            } else {
                OpenFeatureStatus.Error(error)
            }
        }

        else -> OpenFeatureStatus.Error(OpenFeatureError.GeneralError("Unspecified error"))
    }
}