package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test

class ProviderSpecTests {

    @Test
    fun testFlagValueSet() {
        val provider = NoOpProvider()

        val boolResult = provider.getBooleanEvaluation("key", false)
        Assert.assertNotNull(boolResult.value)

        val stringResult = provider.getStringEvaluation("key", "test")
        Assert.assertNotNull(stringResult.value)

        val intResult = provider.getIntegerEvaluation("key", 4)
        Assert.assertNotNull(intResult.value)

        val doubleResult = provider.getDoubleEvaluation("key", 0.4)
        Assert.assertNotNull(doubleResult.value)

        val objectResult = provider.getObjectEvaluation("key", Value.Null)
        Assert.assertNotNull(objectResult.value)
    }

    @Test
    fun testHasReason() {
        val provider = NoOpProvider()
        val boolResult = provider.getBooleanEvaluation("key", false)

        Assert.assertEquals(Reason.DEFAULT.toString(), boolResult.reason)
    }

    @Test
    fun testNoErrorCodeByDefault() {
        val provider = NoOpProvider()
        val boolResult = provider.getBooleanEvaluation("key", false)

        Assert.assertNull(boolResult.errorCode)
    }

    @Test
    fun testVariantIsSet() {
        val provider = NoOpProvider()

        val boolResult = provider.getBooleanEvaluation("key", false)
        Assert.assertNotNull(boolResult.variant)

        val stringResult = provider.getStringEvaluation("key", "test")
        Assert.assertNotNull(stringResult.variant)

        val intResult = provider.getIntegerEvaluation("key", 4)
        Assert.assertNotNull(intResult.variant)

        val doubleResult = provider.getDoubleEvaluation("key", 0.4)
        Assert.assertNotNull(doubleResult.variant)

        val objectResult = provider.getObjectEvaluation("key", Value.Null)
        Assert.assertNotNull(objectResult.variant)
    }
}
