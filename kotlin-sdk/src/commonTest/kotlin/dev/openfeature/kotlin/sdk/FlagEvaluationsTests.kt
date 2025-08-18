package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlagEvaluationsTests {

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testApiSetsProvider() = runTest {
        val provider = NoOpProvider()

        OpenFeatureAPI.setProviderAndWait(provider)
        assertEquals(provider, OpenFeatureAPI.getProvider())
    }

    @Test
    fun testHooksPersist() {
        val hook1 = GenericSpyHookMock()
        val hook2 = GenericSpyHookMock()

        OpenFeatureAPI.addHooks(listOf(hook1))
        assertEquals(1, OpenFeatureAPI.hooks.count())

        OpenFeatureAPI.addHooks(listOf(hook2))
        assertEquals(2, OpenFeatureAPI.hooks.count())
    }

    @Test
    fun testClientHooksPersist() {
        val hook1 = GenericSpyHookMock()
        val hook2 = GenericSpyHookMock()

        val client = OpenFeatureAPI.getClient()
        client.addHooks(listOf(hook1))
        assertEquals(1, client.hooks.count())

        client.addHooks(listOf(hook2))
        assertEquals(2, client.hooks.count())
    }

    @Test
    fun testSimpleFlagEvaluation() = runTest {
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        val client = OpenFeatureAPI.getClient()
        val key = "key"

        assertEquals(true, client.getBooleanValue(key, false))
        assertEquals(true, client.getBooleanValue(key, false, FlagEvaluationOptions()))

        assertEquals("test", client.getStringValue(key, "tset"))
        assertEquals("test", client.getStringValue(key, "tset", FlagEvaluationOptions()))

        assertEquals(400, client.getIntegerValue(key, 4))
        assertEquals(400, client.getIntegerValue(key, 4, FlagEvaluationOptions()))

        assertEquals(40.0, client.getDoubleValue(key, 0.4), 0.0)
        assertEquals(40.0, client.getDoubleValue(key, 0.4, FlagEvaluationOptions()), 0.0)

        assertEquals(Value.Null, client.getObjectValue(key, Value.Structure(mapOf())))
        assertEquals(Value.Null, client.getObjectValue(key, Value.Structure(mapOf()), FlagEvaluationOptions()))
    }

    @Test
    fun testDetailedFlagEvaluation() = runTest {
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        val client = OpenFeatureAPI.getClient()
        val key = "key"

        val booleanDetails = FlagEvaluationDetails(key, true)
        assertEquals(booleanDetails, client.getBooleanDetails(key, false))
        assertEquals(booleanDetails, client.getBooleanDetails(key, false, FlagEvaluationOptions()))

        // in DoSomethingProvider, the string evaluation is special since it contains some metadata values
        val stringDetails = FlagEvaluationDetails(key, "tset", metadata = DoSomethingProvider.evaluationMetadata)
        assertEquals(stringDetails, client.getStringDetails(key, "test"))
        assertEquals(stringDetails, client.getStringDetails(key, "test", FlagEvaluationOptions()))

        val integerDetails = FlagEvaluationDetails(key, 400)
        assertEquals(integerDetails, client.getIntegerDetails(key, 4))
        assertEquals(integerDetails, client.getIntegerDetails(key, 4, FlagEvaluationOptions()))

        val doubleDetails = FlagEvaluationDetails(key, 40.0)
        assertEquals(doubleDetails, client.getDoubleDetails(key, 0.4))
        assertEquals(doubleDetails, client.getDoubleDetails(key, 0.4, FlagEvaluationOptions()))

        val objectDetails = FlagEvaluationDetails<Value>(key, Value.Null)
        assertEquals(objectDetails, client.getObjectDetails(key, Value.Structure(mapOf())))
        assertEquals(objectDetails, client.getObjectDetails(key, Value.Structure(mapOf()), FlagEvaluationOptions()))
    }

    @Test
    fun testMetadataFlagEvaluation() = runTest {
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        val client = OpenFeatureAPI.getClient()
        val key = "key"

        val details = client.getStringDetails(key, "default")
        val metadata: EvaluationMetadata = details.metadata
        assertEquals("value1", metadata.getString("key1"))
        assertEquals(42, metadata.getInt("key2"))
    }

    @Test
    fun testHooksAreFired() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        val client = OpenFeatureAPI.getClient()

        val clientHook = GenericSpyHookMock()
        val invocationHook = GenericSpyHookMock()

        client.addHooks(listOf(clientHook))
        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(invocationHook)))

        assertEquals(1, clientHook.beforeCalled)
        assertEquals(1, invocationHook.beforeCalled)
    }

    @Test
    fun testBrokenProvider() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        val client = OpenFeatureAPI.getClient()

        client.getBooleanValue("testKey", false)
        val details = client.getBooleanDetails("testKey", false)

        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
        assertEquals(Reason.ERROR.toString(), details.reason)
        assertEquals("Could not find flag named: testKey", details.errorMessage)
    }

    @Test
    fun testClientMetadata() {
        val client1 = OpenFeatureAPI.getClient()
        assertNull(client1.metadata.name)

        val client2 = OpenFeatureAPI.getClient("test")
        assertEquals("test", client2.metadata.name)
    }
}