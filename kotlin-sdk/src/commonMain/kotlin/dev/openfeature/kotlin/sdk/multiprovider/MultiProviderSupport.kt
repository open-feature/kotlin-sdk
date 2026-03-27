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

/**
 * Extracts the [OpenFeatureStatus] from a provider.
 *
 * If the provider is wrapped in [MultiProvider.ProviderWithStatus], the wrapped status is returned.
 * Otherwise, defaults to [OpenFeatureStatus.Ready]. This default is safe because [MultiProvider]
 * always wraps providers in [MultiProvider.ProviderWithStatus] before passing them to strategies.
 * If strategies are used directly with unwrapped providers, the provider will be treated as ready.
 */
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
