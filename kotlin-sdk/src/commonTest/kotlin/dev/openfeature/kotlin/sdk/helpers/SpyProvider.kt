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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow

class SpyProvider : FeatureProvider {
    private val statusTracker = ProviderStatusTracker()

    override val status: OpenFeatureStatus get() = statusTracker.status

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    override val hooks: List<Hook<*>>
        get() = TODO("Not yet implemented")
    override val metadata: ProviderMetadata
        get() = TODO("Not yet implemented")

    val initializeCalls = mutableListOf<EvaluationContext?>()
    val onContextSetCalls = mutableListOf<Pair<EvaluationContext?, EvaluationContext>>()
    val shutdownCalls = atomic(0)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
        initializeCalls.add(initialContext)
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
        statusTracker.reset()
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) = statusTracker.reconciling {
        onContextSetCalls.add(Pair(oldContext, newContext))
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        TODO("Not yet implemented")
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        TODO("Not yet implemented")
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        TODO("Not yet implemented")
    }

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
        TODO("Not yet implemented")
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        TODO("Not yet implemented")
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        TODO("Not yet implemented")
    }
}