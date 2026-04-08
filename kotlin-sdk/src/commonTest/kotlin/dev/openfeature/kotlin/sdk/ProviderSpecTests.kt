package dev.openfeature.kotlin.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProviderSpecTests {

    @Test
    fun testFlagValueSet() {
        val provider = NoOpProvider()

        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())
        assertNotNull(boolResult.value)

        val stringResult = provider.getStringEvaluation("key", "test", ImmutableContext())
        assertNotNull(stringResult.value)

        val intResult = provider.getIntegerEvaluation("key", 4, ImmutableContext())
        assertNotNull(intResult.value)

        val longResult = provider.getLongEvaluation("key", Long.MAX_VALUE, ImmutableContext())
        assertNotNull(longResult.value)

        val doubleResult = provider.getDoubleEvaluation("key", 0.4, ImmutableContext())
        assertNotNull(doubleResult.value)

        val objectResult = provider.getObjectEvaluation("key", Value.Null, ImmutableContext())
        assertNotNull(objectResult.value)
    }

    @Test
    fun testHasReason() {
        val provider = NoOpProvider()
        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())

        assertEquals(Reason.DEFAULT.toString(), boolResult.reason)
    }

    @Test
    fun testNoErrorCodeByDefault() {
        val provider = NoOpProvider()
        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())

        assertNull(boolResult.errorCode)
    }

    @Test
    fun testVariantIsSet() {
        val provider = NoOpProvider()

        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())
        assertNotNull(boolResult.variant)

        val stringResult = provider.getStringEvaluation("key", "test", ImmutableContext())
        assertNotNull(stringResult.variant)

        val intResult = provider.getIntegerEvaluation("key", 4, ImmutableContext())
        assertNotNull(intResult.variant)

        val longResult = provider.getLongEvaluation("key", 4L, ImmutableContext())
        assertNotNull(longResult.variant)

        val doubleResult = provider.getDoubleEvaluation("key", 0.4, ImmutableContext())
        assertNotNull(doubleResult.variant)

        val objectResult = provider.getObjectEvaluation("key", Value.Null, ImmutableContext())
        assertNotNull(objectResult.variant)
    }

    @Test
    fun getLongEvaluation_featureProviderDefault() {
        val defaultProvider = object : FeatureProvider {
            override val hooks: List<Hook<*>> = emptyList()

            override val metadata: ProviderMetadata = object : ProviderMetadata {
                override val name: String? = null
            }

            override suspend fun initialize(initialContext: EvaluationContext?) {}

            override fun shutdown() {}

            override suspend fun onContextSet(o: EvaluationContext?, n: EvaluationContext) {}

            override fun getBooleanEvaluation(k: String, d: Boolean, c: EvaluationContext?) = ProviderEvaluation(d)

            override fun getStringEvaluation(k: String, d: String, c: EvaluationContext?) = ProviderEvaluation(d)

            override fun getIntegerEvaluation(k: String, d: Int, c: EvaluationContext?) =
                ProviderEvaluation(d * 2, "v", Reason.DEFAULT.toString())

            override fun getDoubleEvaluation(k: String, d: Double, c: EvaluationContext?) = ProviderEvaluation(d)

            override fun getObjectEvaluation(k: String, d: Value, c: EvaluationContext?) = ProviderEvaluation(d)
        }

        assertEquals(10L, defaultProvider.getLongEvaluation("k", 5L, null).value)
        assertFailsWith<IllegalArgumentException> {
            defaultProvider.getLongEvaluation("k", Long.MAX_VALUE, null)
        }
    }
}