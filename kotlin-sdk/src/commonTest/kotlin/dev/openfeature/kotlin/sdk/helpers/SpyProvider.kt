package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.atomicfu.atomic

class SpyProvider : TrackedProvider(metadata = NamedMetadata("spy")) {
    val initializeCalls = mutableListOf<EvaluationContext?>()
    val onContextSetCalls = mutableListOf<Pair<EvaluationContext?, EvaluationContext>>()
    val shutdownCalls = atomic(0)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        emit(OpenFeatureProviderEvents.ProviderReady())
        initializeCalls.add(initialContext)
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
        super.shutdown()
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) = statusTracker.reconciling {
        onContextSetCalls.add(Pair(oldContext, newContext))
    }
}