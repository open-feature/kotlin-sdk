package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.ProviderStatusTracker
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

class AutoHealingProvider(
    val healDelay: Long = 1000L,
    override val hooks: List<Hook<*>> = emptyList()
) : FeatureProvider {
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = "AutoHealingProvider"
    }
    private var ready = false

    private val statusTracker = ProviderStatusTracker()

    override val status: OpenFeatureStatus get() = statusTracker.status

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        ready = false
        statusTracker.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "AutoHealingProvider got an error. trying to heal",
                    errorCode = ErrorCode.PROVIDER_NOT_READY
                )
            )
        )
        delay(healDelay)
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
        ready = true
    }

    override fun shutdown() {
        ready = false
        statusTracker.reset()
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