package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.StateManagingProvider
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Minimal **legacy** [FeatureProvider] (not [StateManagingProvider]) for tests that must exercise the
 * SDK-managed status / [FeatureProvider.observe] mirror path.
 */
class LegacyMinimalProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = "legacy-minimal"
    }
) : FeatureProvider {
    val shutdownCalls = atomic(0)

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {}

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> = ProviderEvaluation(defaultValue)

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = ProviderEvaluation(defaultValue)

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = ProviderEvaluation(defaultValue)

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = ProviderEvaluation(defaultValue)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = ProviderEvaluation(defaultValue)
}