package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode
import dev.openfeature.sdk.helpers.AlwaysBrokenProvider
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

@ExperimentalCoroutinesApi
class DeveloperExperienceTests {
    @Test
    fun testNoProviderSet() = runTest {
        OpenFeatureAPI.clearProvider()
        val stringValue = OpenFeatureAPI.getClient().getStringValue("test", "no-op")
        Assert.assertEquals(stringValue, "no-op")
    }

    @Test
    fun testSimpleBooleanFlag() = runTest {
        OpenFeatureAPI.setProvider(NoOpProvider(), ImmutableContext())
        val booleanValue = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        Assert.assertFalse(booleanValue)
    }

    @Test
    fun testClientHooks() = runTest {
        OpenFeatureAPI.setProvider(NoOpProvider(), ImmutableContext())
        val client = OpenFeatureAPI.getClient()

        val hook = GenericSpyHookMock()
        client.addHooks(listOf(hook))

        client.getBooleanValue("test", false)
        Assert.assertEquals(hook.finallyCalledAfter, 1)
    }

    @Test
    fun testEvalHooks() = runTest {
        OpenFeatureAPI.setProvider(NoOpProvider(), ImmutableContext())
        val client = OpenFeatureAPI.getClient()

        val hook = GenericSpyHookMock()
        val options = FlagEvaluationOptions(listOf(hook))

        client.getBooleanValue("test", false, options)
        Assert.assertEquals(hook.finallyCalledAfter, 1)
    }

    @Test
    fun testBrokenProvider() = runTest {
        OpenFeatureAPI.setProvider(AlwaysBrokenProvider(), ImmutableContext())
        val client = OpenFeatureAPI.getClient()

        val details = client.getBooleanDetails("test", false)
        Assert.assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
        Assert.assertEquals("Could not find flag named: test", details.errorMessage)
        Assert.assertEquals(Reason.ERROR.toString(), details.reason)
    }
}