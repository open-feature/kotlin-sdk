package dev.openfeature.kotlin.sdk.events

import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

sealed interface OpenFeatureProviderEvents {
    object ProviderReady : OpenFeatureProviderEvents

    @Deprecated("Use ProviderError instead", ReplaceWith("ProviderError"))
    object ProviderNotReady : OpenFeatureProviderEvents
    object ProviderStale : OpenFeatureProviderEvents
    data class ProviderError(val error: OpenFeatureError) : OpenFeatureProviderEvents
    object ProviderConfigurationChanged : OpenFeatureProviderEvents
}