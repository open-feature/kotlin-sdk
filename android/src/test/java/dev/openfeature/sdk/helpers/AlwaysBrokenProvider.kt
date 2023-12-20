package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderMetadata
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.exceptions.OpenFeatureError.FlagNotFoundError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class AlwaysBrokenProvider(
    override var hooks: List<Hook<*>> = listOf(),
    override var metadata: ProviderMetadata = AlwaysBrokenProviderMetadata()
) :
    FeatureProvider {
    override fun initialize(initialContext: EvaluationContext?) {
        // no-op
    }

    override fun shutdown() {
        // no-op
    }

    override fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // no-op
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        throw FlagNotFoundError(key)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        throw FlagNotFoundError(key)
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        throw FlagNotFoundError(key)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        throw FlagNotFoundError(key)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        throw FlagNotFoundError(key)
    }

    override fun observe(): Flow<OpenFeatureEvents> = flow { }

    override fun isProviderReady(): Boolean = true

    override fun getProviderStatus(): OpenFeatureEvents =
        OpenFeatureEvents.ProviderError(FlagNotFoundError("test"))

    class AlwaysBrokenProviderMetadata(override val name: String? = "test") : ProviderMetadata
}