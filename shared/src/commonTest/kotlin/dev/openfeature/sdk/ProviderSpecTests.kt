package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test

class ProviderSpecTests {

    @Test
    fun testFlagValueSet() {
        val provider = NoOpProvider()

        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())
        Assert.assertNotNull(boolResult.value)

        val stringResult = provider.getStringEvaluation("key", "test", ImmutableContext())
        Assert.assertNotNull(stringResult.value)

        val intResult = provider.getIntegerEvaluation("key", 4, ImmutableContext())
        Assert.assertNotNull(intResult.value)

        val doubleResult = provider.getDoubleEvaluation("key", 0.4, ImmutableContext())
        Assert.assertNotNull(doubleResult.value)

        val objectResult = provider.getObjectEvaluation("key", Value.Null, ImmutableContext())
        Assert.assertNotNull(objectResult.value)
    }

    @Test
    fun testHasReason() {
        val provider = NoOpProvider()
        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())

        Assert.assertEquals(Reason.DEFAULT.toString(), boolResult.reason)
    }

    @Test
    fun testNoErrorCodeByDefault() {
        val provider = NoOpProvider()
        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())

        Assert.assertNull(boolResult.errorCode)
    }

    @Test
    fun testVariantIsSet() {
        val provider = NoOpProvider()

        val boolResult = provider.getBooleanEvaluation("key", false, ImmutableContext())
        Assert.assertNotNull(boolResult.variant)

        val stringResult = provider.getStringEvaluation("key", "test", ImmutableContext())
        Assert.assertNotNull(stringResult.variant)

        val intResult = provider.getIntegerEvaluation("key", 4, ImmutableContext())
        Assert.assertNotNull(intResult.variant)

        val doubleResult = provider.getDoubleEvaluation("key", 0.4, ImmutableContext())
        Assert.assertNotNull(doubleResult.variant)

        val objectResult = provider.getObjectEvaluation("key", Value.Null, ImmutableContext())
        Assert.assertNotNull(objectResult.variant)
    }
}