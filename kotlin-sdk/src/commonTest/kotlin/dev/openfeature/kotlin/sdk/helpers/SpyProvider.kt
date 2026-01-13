package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import kotlinx.atomicfu.atomic

class SpyProvider : FeatureProvider {
    override val hooks: List<Hook<*>>
        get() = TODO("Not yet implemented")
    override val metadata: ProviderMetadata
        get() = TODO("Not yet implemented")

    val initializeCalls = mutableListOf<EvaluationContext?>()
    val onContextSetCalls = mutableListOf<Pair<EvaluationContext?, EvaluationContext>>()
    val shutdownCalls = atomic(0)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        initializeCalls.add(initialContext)
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
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