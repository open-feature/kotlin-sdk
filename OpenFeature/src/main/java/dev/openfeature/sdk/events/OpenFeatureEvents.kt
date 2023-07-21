package dev.openfeature.sdk.events

sealed class OpenFeatureEvents {
    object ProviderReady : OpenFeatureEvents()
    object ProviderConfigurationChanged : OpenFeatureEvents()
    data class ProviderError(val error: Throwable) : OpenFeatureEvents()
    object ProviderStale : OpenFeatureEvents()
    object ProviderShutDown : OpenFeatureEvents()
}