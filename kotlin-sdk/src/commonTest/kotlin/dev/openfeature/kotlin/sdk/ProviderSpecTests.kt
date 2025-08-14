package dev.openfeature.kotlin.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
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

        val doubleResult = provider.getDoubleEvaluation("key", 0.4, ImmutableContext())
        assertNotNull(doubleResult.variant)

        val objectResult = provider.getObjectEvaluation("key", Value.Null, ImmutableContext())
        assertNotNull(objectResult.variant)
    }
}