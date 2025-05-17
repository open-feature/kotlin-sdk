package dev.openfeature.sdk.events

import dev.openfeature.sdk.exceptions.OpenFeatureError

sealed interface OpenFeatureProviderEvents {
    object ProviderReady : OpenFeatureProviderEvents

    @Deprecated("Use ProviderError instead", ReplaceWith("ProviderError"))
    object ProviderNotReady : OpenFeatureProviderEvents
    object ProviderStale : OpenFeatureProviderEvents
    data class ProviderError(val error: OpenFeatureError) : OpenFeatureProviderEvents
    object ProviderConfigurationChanged : OpenFeatureProviderEvents
}