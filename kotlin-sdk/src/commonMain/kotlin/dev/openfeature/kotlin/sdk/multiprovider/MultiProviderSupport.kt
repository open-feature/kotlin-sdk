package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.OpenFeatureStatus

private const val undefinedProviderName = "<unnamed>"

internal fun unwrapProvider(provider: FeatureProvider): FeatureProvider {
    return when (provider) {
        is MultiProvider.ProviderWithStatus -> unwrapProvider(provider.provider)
        is MultiProvider.ChildFeatureProvider -> provider.provider
        else -> provider
    }
}

internal fun providerStatus(provider: FeatureProvider): OpenFeatureStatus {
    return (provider as? MultiProvider.ProviderWithStatus)?.status ?: OpenFeatureStatus.Ready
}

internal fun shouldEvaluateProvider(provider: FeatureProvider): Boolean {
    return when (providerStatus(provider)) {
        is OpenFeatureStatus.NotReady,
        is OpenFeatureStatus.Fatal -> false
        else -> true
    }
}

internal fun providerDisplayName(provider: FeatureProvider): String {
    return when (provider) {
        is MultiProvider.ProviderWithStatus -> providerDisplayName(provider.provider)
        is MultiProvider.ChildFeatureProvider -> provider.name
        else -> provider.metadata.name ?: undefinedProviderName
    }
}