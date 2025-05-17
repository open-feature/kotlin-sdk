package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderMetadata
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class AutoHealingProvider(
    val healDelay: Long = 1000L,
    override val hooks: List<Hook<*>> = emptyList()
) : FeatureProvider {
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = "AutoHealingProvider"
    }
    private var ready = false
    private val _events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)
    override suspend fun initialize(initialContext: EvaluationContext?) {
        ready = false
        _events.emit(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureError.ProviderNotReadyError(
                    "AutoHealingProvider got an error. trying to heal"
                )
            )
        )
        delay(healDelay)
        _events.emit(OpenFeatureProviderEvents.ProviderReady)
        ready = true
    }

    override fun shutdown() {
        // no-op
    }

    override suspend fun onContextSet(
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

    override fun observe(): Flow<OpenFeatureProviderEvents> {
        return _events
    }
}