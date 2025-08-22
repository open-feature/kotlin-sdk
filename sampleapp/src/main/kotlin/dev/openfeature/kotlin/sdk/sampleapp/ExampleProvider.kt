package dev.openfeature.kotlin.sdk.sampleapp

import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ExampleProvider(
    providerName: String,
    private val flags: Map<String, Any>,
    override val hooks: List<Hook<*>> = listOf()
) : FeatureProvider {
    private var currentContext: EvaluationContext? = ImmutableContext()
    var delayTime = 1000L
    var returnDefaults = false
    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>()

    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = providerName
    }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        currentContext = initialContext
        // Simulate a delay in the provider initialization
        delay(delayTime)
        eventFlow.emit(OpenFeatureProviderEvents.ProviderReady)
    }

    override fun shutdown() {

    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = eventFlow.asSharedFlow()

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        currentContext = newContext
        delay(delayTime)
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> = generateProviderEvaluation<Boolean>(defaultValue, key)

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = generateProviderEvaluation<String>(defaultValue, key)

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = generateProviderEvaluation<Int>(defaultValue, key)

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = generateProviderEvaluation<Double>(defaultValue, key)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = generateProviderEvaluation<Value>(defaultValue, key)

    private inline fun <reified T> generateProviderEvaluation(
        defaultValue: T,
        key: String
    ): ProviderEvaluation<T> {
        if (returnDefaults) {
            return ProviderEvaluation(defaultValue, null, reason = "returnDefaults")
        }
        return with(flags) {
            if (containsKey(key) && get(key) is T) {
                ProviderEvaluation(get(key) as T, "variant1", reason = "match")
            } else if (containsKey(key)) {
                ProviderEvaluation(defaultValue, null, reason = "invalid type")
            } else {
                ProviderEvaluation(defaultValue, null, reason = "notfound", errorCode = ErrorCode.FLAG_NOT_FOUND)
            }
        }
    }
}