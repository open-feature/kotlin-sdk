package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode
import dev.openfeature.sdk.helpers.BrokenInitProvider
import dev.openfeature.sdk.helpers.DoSomethingProvider
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Test

class FlagEvaluationsTests {

    @After
    fun tearDown() {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testApiSetsProvider() = runTest {
        val provider = NoOpProvider()

        OpenFeatureAPI.setProviderAndWait(provider)
        Assert.assertEquals(provider, OpenFeatureAPI.getProvider())
    }

    @Test
    fun testHooksPersist() {
        val hook1 = GenericSpyHookMock()
        val hook2 = GenericSpyHookMock()

        OpenFeatureAPI.addHooks(listOf(hook1))
        Assert.assertEquals(1, OpenFeatureAPI.hooks.count())

        OpenFeatureAPI.addHooks(listOf(hook2))
        Assert.assertEquals(2, OpenFeatureAPI.hooks.count())
    }

    @Test
    fun testClientHooksPersist() {
        val hook1 = GenericSpyHookMock()
        val hook2 = GenericSpyHookMock()

        val client = OpenFeatureAPI.getClient()
        client.addHooks(listOf(hook1))
        Assert.assertEquals(1, client.hooks.count())

        client.addHooks(listOf(hook2))
        Assert.assertEquals(2, client.hooks.count())
    }

    @Test
    fun testSimpleFlagEvaluation() = runTest {
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        val client = OpenFeatureAPI.getClient()
        val key = "key"

        Assert.assertEquals(true, client.getBooleanValue(key, false))
        Assert.assertEquals(true, client.getBooleanValue(key, false, FlagEvaluationOptions()))

        Assert.assertEquals("test", client.getStringValue(key, "tset"))
        Assert.assertEquals("test", client.getStringValue(key, "tset", FlagEvaluationOptions()))

        Assert.assertEquals(400, client.getIntegerValue(key, 4))
        Assert.assertEquals(400, client.getIntegerValue(key, 4, FlagEvaluationOptions()))

        Assert.assertEquals(40.0, client.getDoubleValue(key, 0.4), 0.0)
        Assert.assertEquals(40.0, client.getDoubleValue(key, 0.4, FlagEvaluationOptions()), 0.0)

        Assert.assertEquals(Value.Null, client.getObjectValue(key, Value.Structure(mapOf())))
        Assert.assertEquals(Value.Null, client.getObjectValue(key, Value.Structure(mapOf()), FlagEvaluationOptions()))
    }

    @Test
    fun testDetailedFlagEvaluation() = runTest {
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        val client = OpenFeatureAPI.getClient()
        val key = "key"

        val booleanDetails = FlagEvaluationDetails(key, true)
        Assert.assertEquals(booleanDetails, client.getBooleanDetails(key, false))
        Assert.assertEquals(booleanDetails, client.getBooleanDetails(key, false, FlagEvaluationOptions()))

        // in DoSomethingProvider, the string evaluation is special since it contains some metadata values
        val stringDetails = FlagEvaluationDetails(key, "tset", metadata = DoSomethingProvider.evaluationMetadata)
        Assert.assertEquals(stringDetails, client.getStringDetails(key, "test"))
        Assert.assertEquals(stringDetails, client.getStringDetails(key, "test", FlagEvaluationOptions()))

        val integerDetails = FlagEvaluationDetails(key, 400)
        Assert.assertEquals(integerDetails, client.getIntegerDetails(key, 4))
        Assert.assertEquals(integerDetails, client.getIntegerDetails(key, 4, FlagEvaluationOptions()))

        val doubleDetails = FlagEvaluationDetails(key, 40.0)
        Assert.assertEquals(doubleDetails, client.getDoubleDetails(key, 0.4))
        Assert.assertEquals(doubleDetails, client.getDoubleDetails(key, 0.4, FlagEvaluationOptions()))

        val objectDetails = FlagEvaluationDetails(key, Value.Null)
        Assert.assertEquals(objectDetails, client.getObjectDetails(key, Value.Structure(mapOf())))
        Assert.assertEquals(objectDetails, client.getObjectDetails(key, Value.Structure(mapOf()), FlagEvaluationOptions()))
    }

    @Test
    fun testMetadataFlagEvaluation() = runTest {
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        val client = OpenFeatureAPI.getClient()
        val key = "key"

        val details = client.getStringDetails(key, "default")
        val metadata: EvaluationMetadata = details.metadata
        Assert.assertEquals("value1", metadata.getString("key1"))
        Assert.assertEquals(42, metadata.getInt("key2"))
    }

    @Test
    fun testHooksAreFired() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        val client = OpenFeatureAPI.getClient()

        val clientHook = GenericSpyHookMock()
        val invocationHook = GenericSpyHookMock()

        client.addHooks(listOf(clientHook))
        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(invocationHook)))

        Assert.assertEquals(1, clientHook.beforeCalled)
        Assert.assertEquals(1, invocationHook.beforeCalled)
    }

    @Test
    fun testBrokenProvider() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        val client = OpenFeatureAPI.getClient()

        client.getBooleanValue("testKey", false)
        val details = client.getBooleanDetails("testKey", false)

        Assert.assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
        Assert.assertEquals(Reason.ERROR.toString(), details.reason)
        Assert.assertEquals("Could not find flag named: testKey", details.errorMessage)
    }

    @Test
    fun testClientMetadata() {
        val client1 = OpenFeatureAPI.getClient()
        Assert.assertNull(client1.metadata.name)

        val client2 = OpenFeatureAPI.getClient("test")
        Assert.assertEquals("test", client2.metadata.name)
    }
}