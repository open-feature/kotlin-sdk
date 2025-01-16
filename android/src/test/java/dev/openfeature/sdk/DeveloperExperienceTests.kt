package dev.openfeature.sdk

import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.exceptions.ErrorCode
import dev.openfeature.sdk.helpers.AlwaysBrokenProvider
import dev.openfeature.sdk.helpers.AutoHealingProvider
import dev.openfeature.sdk.helpers.DoSomethingProvider
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import dev.openfeature.sdk.helpers.SlowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Test
    fun testSetProviderAndWaitReady() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        CoroutineScope(dispatcher).launch {
            OpenFeatureAPI.setProviderAndWait(SlowProvider(dispatcher = dispatcher), dispatcher, ImmutableContext())
        }
        testScheduler.advanceTimeBy(1) // Make sure setProviderAndWait is called
        val booleanValue1 = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        Assert.assertFalse(booleanValue1)
        testScheduler.advanceTimeBy(10000) // SlowProvider is now Ready
        val booleanValue2 = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        Assert.assertTrue(booleanValue2)
    }

    @Test
    fun testSetProviderAndWaitError() = runTest {
        val dispatcher = UnconfinedTestDispatcher()
        OpenFeatureAPI.setProviderAndWait(AlwaysBrokenProvider(), dispatcher, ImmutableContext())
        val booleanValue = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        Assert.assertFalse(booleanValue)
    }

    @Test
    fun testObserveEvents() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var eventCount = 0
        CoroutineScope(dispatcher).launch {
            OpenFeatureAPI.observe<OpenFeatureEvents.ProviderReady>().collect {
                eventCount++
            }
        }
        CoroutineScope(dispatcher).launch {
            OpenFeatureAPI.setProviderAndWait(
                DoSomethingProvider(dispatcher = dispatcher),
                dispatcher,
                ImmutableContext()
            )
        }
        advanceUntilIdle()
        Assert.assertEquals(eventCount, 1)
    }

    @Test
    fun testProviderThatHealsWithErrorThenReady() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val healing = AutoHealingProvider(dispatcher = dispatcher, healDelay = 100)
        val resultEvents = mutableListOf<OpenFeatureEvents>()
        val r = async {
            OpenFeatureAPI.observe<OpenFeatureEvents>().toCollection(resultEvents)
        }
        OpenFeatureAPI.setProviderAndWait(healing, dispatcher, ImmutableContext())
        testScheduler.advanceUntilIdle()
        Assert.assertEquals(2, resultEvents.size)
        Assert.assertTrue(resultEvents[0] is OpenFeatureEvents.ProviderError)
        Assert.assertEquals(OpenFeatureEvents.ProviderReady, resultEvents[1])
        OpenFeatureAPI.shutdown()
        r.cancel()
    }
}