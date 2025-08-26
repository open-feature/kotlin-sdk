package dev.openfeature.kotlin.sdk.sampleapp

import dev.openfeature.kotlin.sdk.*
import kotlinx.coroutines.delay

class ExampleProvider(override val hooks: List<Hook<*>> = listOf()) : FeatureProvider {

    private var currentContext: EvaluationContext? = ImmutableContext()
    var delayTime = 1000L
    var returnDefaults = false
    val flags = mutableMapOf<String, Any>().apply {
        put("booleanFlag", true)
        put("stringFlag", "this is a string")
        put("intFlag", 1337)
        put("doubleFlag", 42.0)
        put(
            "objectFlag",
            Value.Structure(mapOf("key1" to Value.String("value"), "key2" to Value.Integer(10)))
        )
    }

    override val metadata: ProviderMetadata
        get() = object : ProviderMetadata {
            override val name: String = "ExampleProvider"
        }

    override suspend fun initialize(initialContext: EvaluationContext?) {
        currentContext = initialContext
        // Simulate a delay in the provider initialization
        delay(delayTime)
    }

    override fun shutdown() {

    }

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
                ProviderEvaluation(defaultValue, null, reason = "notfound")
            }
        }
    }
}