package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay

class AutoHealingProvider(
    val healDelay: Long = 1000L,
    hooks: List<Hook<*>> = emptyList()
) : TrackedProvider(hooks, NamedMetadata("AutoHealingProvider")) {
    private val readyState = atomic(false)
    private var ready: Boolean
        get() = readyState.value
        set(value) {
            readyState.value = value
        }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        ready = false
        emit(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "AutoHealingProvider got an error. trying to heal",
                    errorCode = ErrorCode.PROVIDER_NOT_READY
                )
            )
        )
        delay(healDelay)
        ready = true
        emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        ready = false
        super.shutdown()
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

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
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
}