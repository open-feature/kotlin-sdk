package dev.openfeature.sdk.events

sealed class OpenFeatureEvents {
    object ProviderReady : OpenFeatureEvents()
    object ProviderConfigurationChanged : OpenFeatureEvents()
    object ProviderError : OpenFeatureEvents()
    object ProviderStale : OpenFeatureEvents()
    object ProviderShutDown : OpenFeatureEvents()
}