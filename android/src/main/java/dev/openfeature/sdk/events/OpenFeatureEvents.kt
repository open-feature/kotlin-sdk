package dev.openfeature.sdk.events

sealed interface OpenFeatureEvents {
    object ProviderNotReady : OpenFeatureEvents
    object ProviderReady : OpenFeatureEvents
    data class ProviderError(val error: Throwable) : OpenFeatureEvents
    object ProviderStale : OpenFeatureEvents
}