package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class SlowProvider(
    override val hooks: List<Hook<*>> = listOf(),
    private var dispatcher: CoroutineDispatcher,
    override val metadata: ProviderMetadata = SlowProviderMetadata("Slow provider")
) : FeatureProvider {
    internal var ready = false
    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        CoroutineScope(dispatcher).async {
            delay(2000)
        }.await()
        ready = true
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override fun shutdown() {
        // no-op
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        CoroutineScope(dispatcher).async {
            delay(2000)
        }.await()
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(!defaultValue)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(defaultValue.reversed())
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(defaultValue * 100)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        if (!ready) throw OpenFeatureError.FlagNotFoundError(key)
        return ProviderEvaluation(Value.Null)
    }

    data class SlowProviderMetadata(override val name: String?) : ProviderMetadata
}