package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.FeatureProvider

abstract class BaseEvaluationStrategy : MultiProvider.Strategy {
    protected fun shouldEvaluate(provider: FeatureProvider): Boolean {
        return shouldEvaluateProvider(provider)
    }

    protected fun providerName(provider: FeatureProvider): String {
        return providerDisplayName(provider)
    }
}
