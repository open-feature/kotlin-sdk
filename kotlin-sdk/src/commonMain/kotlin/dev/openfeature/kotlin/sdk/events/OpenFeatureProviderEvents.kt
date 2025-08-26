package dev.openfeature.kotlin.sdk.events

import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

sealed class OpenFeatureProviderEvents {
    data class EventDetails(
        val flagsChanged: List<String> = emptyList(),
        val message: String? = null,
        val errorCode: ErrorCode? = null,
        val eventMetadata: Map<String, Any> = emptyMap()
    )

    abstract val eventDetails: EventDetails

    /**
     * The provider is ready to perform flag evaluations.
     */
    data class ProviderReady(
        override val eventDetails: EventDetails
    ) : OpenFeatureProviderEvents()

    /**
     * The provider signaled an error.
     */
    data class ProviderError(
        override val eventDetails: EventDetails = EventDetails(),
        @Deprecated("Please use eventDetails instead.") val error: OpenFeatureError? = null
    ) : OpenFeatureProviderEvents()

    data class ProviderConfigurationChanged(
        override val eventDetails: EventDetails
    ) : OpenFeatureProviderEvents()

    /**
     * The provider's cached state is no longer valid and may not be up-to-date with the source of truth.
     */
    data class ProviderStale(
        override val eventDetails: EventDetails
    ) : OpenFeatureProviderEvents()

    /**
     * The context associated with the provider has changed, and the provider has not yet reconciled its associated state.
     */
    data class ProviderReconciling(
        override val eventDetails: EventDetails
    ) : OpenFeatureProviderEvents()

    /**
     * The context associated with the provider has changed, and the provider has reconciled its associated state.
     */
    data class ProviderContextChanged(
        override val eventDetails: EventDetails
    ) : OpenFeatureProviderEvents()

    @Deprecated("Use ProviderError instead", ReplaceWith("ProviderError"))
    data object ProviderNotReady : OpenFeatureProviderEvents() {
        override val eventDetails = EventDetails()
    }
}