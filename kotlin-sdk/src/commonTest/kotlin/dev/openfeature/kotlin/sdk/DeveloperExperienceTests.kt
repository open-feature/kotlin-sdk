package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.AutoHealingProvider
import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import dev.openfeature.kotlin.sdk.helpers.OverlyEmittingProvider
import dev.openfeature.kotlin.sdk.helpers.SlowProvider
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperExperienceTests {
    @AfterTest
    fun tearDown() = runTest {
        // It becomes important to clear the provider after each test since the SDK is a singleton
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testNoProviderSet() = runTest {
        OpenFeatureAPI.clearProvider()
        val stringValue = OpenFeatureAPI.getClient().getStringValue("test", "no-op")
        assertEquals(stringValue, "no-op")
    }

    @Test
    fun testSimpleBooleanFlag() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider(), ImmutableContext())
        val booleanValue = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        assertFalse(booleanValue)
    }

    @Test
    fun testSetProviderWithDefaultDispatcher() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProvider(
            SlowProvider(dispatcher = testDispatcher),
            initialContext = ImmutableContext()
        )
        advanceUntilIdle()
        OpenFeatureAPI.statusFlow.firstOrNull { it is OpenFeatureStatus.Ready }
        val booleanDetails = OpenFeatureAPI.getClient().getBooleanDetails("test", false)
        assertNull(booleanDetails.errorCode)
        assertNull(booleanDetails.errorMessage)
    }

    @Test
    fun testSetProviderWithTestDispatcher() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProvider(
            NoOpProvider(),
            dispatcher = testDispatcher,
            initialContext = ImmutableContext()
        )
        testScheduler.advanceUntilIdle() // Make sure coroutine in setProvider is called
        val booleanDetails = OpenFeatureAPI.getClient().getBooleanDetails("test", false)
        assertNull(booleanDetails.errorCode)
        assertNull(booleanDetails.errorMessage)
    }

    @Test
    fun testSetProviderCancelsLastSetProvider() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val slowProvider1 =
            SlowProvider(
                dispatcher = testDispatcher,
                metadata = object : ProviderMetadata {
                    override val name: String = "Slow Provider 1"
                }
            )
        val slowProvider2 = SlowProvider(
            dispatcher = testDispatcher,
            metadata = object : ProviderMetadata {
                override val name: String = "Slow Provider 2"
            }
        )
        OpenFeatureAPI.setProvider(
            slowProvider1,
            dispatcher = testDispatcher,
            initialContext = ImmutableContext()
        )
        OpenFeatureAPI.setProvider(
            slowProvider2,
            dispatcher = testDispatcher,
            initialContext = ImmutableContext()
        )
        testScheduler.advanceUntilIdle()
        // slowProvider1 should not be ready since its coroutine was cancelled
        assertFalse(slowProvider1.ready)
        assertTrue(slowProvider2.ready)
        assertEquals(OpenFeatureAPI.getProvider().metadata.name, "Slow Provider 2")
    }

    @Test
    fun testClientHooks() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider(), ImmutableContext())
        val client = OpenFeatureAPI.getClient()

        val hook = GenericSpyHookMock()
        client.addHooks(listOf(hook))

        client.getBooleanValue("test", false)
        assertEquals(hook.finallyCalledAfter, 1)
    }

    @Test
    fun testEvalHooks() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider(), ImmutableContext())
        val client = OpenFeatureAPI.getClient()

        val hook = GenericSpyHookMock()
        val options = FlagEvaluationOptions(listOf(hook))

        client.getBooleanValue("test", false, options)
        assertEquals(hook.finallyCalledAfter, 1)
    }

    @Test
    fun testBrokenProvider() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider(), ImmutableContext())
        testScheduler.advanceUntilIdle()
        val client = OpenFeatureAPI.getClient()

        val details = client.getBooleanDetails("test", false)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
        assertEquals("Could not find flag named: test", details.errorMessage)
        assertEquals(Reason.ERROR.toString(), details.reason)
    }

    @Test
    fun testSetProviderAndWaitReady() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val job = CoroutineScope(dispatcher).launch {
            OpenFeatureAPI.setProviderAndWait(
                SlowProvider(dispatcher = dispatcher),
                ImmutableContext()
            )
        }
        testScheduler.advanceTimeBy(1) // Make sure setProviderAndWait is called
        val booleanValue1 = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        assertFalse(booleanValue1)
        testScheduler.advanceTimeBy(10000) // SlowProvider is now Ready
        val booleanValue2 = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        assertTrue(booleanValue2)
        job.cancelAndJoin()
    }

    @Test
    fun testSetProviderAndWaitError() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider(), ImmutableContext())
        val booleanValue = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        assertFalse(booleanValue)
    }

    @Test
    fun testStatusFlow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val emittedStatuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                emittedStatuses.add(it)
            }
        }
        // start out with Not Ready
        OpenFeatureAPI.setProviderAndWait(
            SlowProvider(dispatcher = dispatcher),
            ImmutableContext(targetingKey = "0")
        )
        testScheduler.advanceUntilIdle()
        // After 2 seconds the slow provider is ready
        // Setting a new context should cause the provider to reconcile
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(targetingKey = "tk"))
        testScheduler.advanceUntilIdle()
        // After 2 seconds the slow provider is ready again
        // Shutting down should cause the status to be NotReady
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(
            listOf(
                OpenFeatureStatus.NotReady,
                OpenFeatureStatus.Ready,
                OpenFeatureStatus.Reconciling,
                OpenFeatureStatus.Ready,
                OpenFeatureStatus.NotReady
            ),
            emittedStatuses
        )
    }

    @Test
    fun testStatusFlowWithErrors() = runTest {
        val emittedStatuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                println(it)
                emittedStatuses.add(it)
            }
        }
        testScheduler.advanceUntilIdle()
        // start out with Not Ready
        OpenFeatureAPI.setProviderAndWait(
            BrokenInitProvider(),
            ImmutableContext(targetingKey = "0")
        )
        testScheduler.advanceUntilIdle()

        // setting a context will make it reconciling and then ready
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(targetingKey = "new"))
        testScheduler.advanceTimeBy(100)

        // Shutting down should cause the status to be NotReady
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(5, emittedStatuses.size)
        assertTrue(emittedStatuses[0] is OpenFeatureStatus.NotReady)
        assertTrue(emittedStatuses[1] is OpenFeatureStatus.Error)
        assertTrue((emittedStatuses[1] as OpenFeatureStatus.Error).error is OpenFeatureError.ProviderNotReadyError)
        assertTrue(emittedStatuses[2] is OpenFeatureStatus.Reconciling)
        assertTrue(emittedStatuses[3] is OpenFeatureStatus.Ready)
        assertTrue(emittedStatuses[4] is OpenFeatureStatus.NotReady)
    }

    @Test
    fun testProviderThatErrorsButHealsThenReady() = runTest {
        val healDelayMillis: Long = 100
        val healing = AutoHealingProvider(healDelay = healDelayMillis)
        val job = async {
            OpenFeatureAPI.setProviderAndWait(healing, ImmutableContext())
        }
        waitAssert {
            assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        }
        waitAssert {
            assertTrue(OpenFeatureAPI.getStatus() is OpenFeatureStatus.Error)
        }
        waitAssert {
            assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        }
        job.cancelAndJoin()
        OpenFeatureAPI.shutdown()
        advanceUntilIdle()
    }

    @Test
    fun testStatusFlowShouldSupportSwappingProviders() = runTest {
        advanceUntilIdle()
        val firstProvider = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "First Provider"
            }
        )
        val secondProvider = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "Second Provider"
            }
        )
        val emittedStatuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                emittedStatuses.add(it)
            }
        }

        OpenFeatureAPI.setProviderAndWait(
            firstProvider,
            initialContext = ImmutableContext("first")
        )
        testScheduler.advanceUntilIdle()
        waitAssert {
            assertEquals(listOf(OpenFeatureStatus.NotReady, OpenFeatureStatus.Ready), emittedStatuses)
        }
        OpenFeatureAPI.setProviderAndWait(
            secondProvider,
            initialContext = ImmutableContext("second")
        )
        testScheduler.advanceUntilIdle()
        waitAssert {
            assertEquals(
                listOf(
                    OpenFeatureStatus.NotReady,
                    OpenFeatureStatus.Ready,
                    OpenFeatureStatus.NotReady,
                    OpenFeatureStatus.Ready
                ),
                emittedStatuses
            )
        }
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()

        waitAssert {
            assertEquals(
                listOf(
                    OpenFeatureStatus.NotReady,
                    OpenFeatureStatus.Ready,
                    OpenFeatureStatus.NotReady,
                    OpenFeatureStatus.Ready,
                    OpenFeatureStatus.NotReady
                ),
                emittedStatuses
            )
        }
        job.cancelAndJoin()
    }

    @Test
    fun testProviderEventFlowShouldSupportFiltering() = runTest {
        val provider = OverlyEmittingProvider("Overly Emitting Provider")
        val staleEvents = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            // only collect events of type stale.
            OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderStale>().collect {
                staleEvents.add(it)
            }
        }

        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            provider,
            initialContext = ImmutableContext("first")
        )
        // emits ProviderStale + ProviderStale + ProviderStale
        OpenFeatureAPI.getClient().track("hello-world")

        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("second"))
        testScheduler.advanceUntilIdle()

        OpenFeatureAPI.shutdown()
        job.cancelAndJoin()
        assertEquals(
            listOf<OpenFeatureProviderEvents>(
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            ),
            staleEvents
        )
    }

    @Test
    fun setEvaluationContextDoesDeepEqualsOnAttributes() = runTest {
        val map = mutableMapOf<String, Value>()
        val provider = SpyProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        assertEquals(1, provider.initializeCalls.size)
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(attributes = map))
        assertEquals(1, provider.onContextSetCalls.size)
        map["myKey"] = Value.String("myValue")
        println(OpenFeatureAPI.getEvaluationContext()?.asMap())
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(attributes = map))
        assertEquals(2, provider.onContextSetCalls.size)
    }

    @Test
    fun setEvaluationContextDoesNotDetectDeepChangesInNestedStructures() = runTest {
        val nestedMap = mutableMapOf<String, Value>()
        val map = mutableMapOf<String, Value>()
        map["nested"] = Value.Structure(nestedMap)

        val provider = SpyProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        assertEquals(1, provider.initializeCalls.size)

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(attributes = map))
        assertEquals(1, provider.onContextSetCalls.size)

        // This won't update the `nested` list in `map`, since Value.Structure returns an immutable copy
        nestedMap["deepKey"] = Value.String("deepValue")

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(attributes = map))
        assertEquals(1, provider.onContextSetCalls.size)

        val currentContext = OpenFeatureAPI.getEvaluationContext()
        val nestedStructure = currentContext?.getValue("nested")?.asStructure()
        assertNull(nestedStructure?.get("deepKey")?.asString())
    }

    @Test
    fun setEvaluationContextDoesNotDetectDeepChangesInNestedLists() = runTest {
        val nestedList = mutableListOf<Value>()
        val map = mutableMapOf<String, Value>()
        map["nested"] = Value.List(nestedList)

        val provider = SpyProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        assertEquals(1, provider.initializeCalls.size)

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(attributes = map))
        assertEquals(1, provider.onContextSetCalls.size)

        // This won't update the `nested` list in `map`, since Value.List returns an immutable copy
        nestedList.add(Value.String("deepValue"))

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(attributes = map))
        assertEquals(1, provider.onContextSetCalls.size)

        val currentContext = OpenFeatureAPI.getEvaluationContext()
        val nestedListFromContext = currentContext?.getValue("nested")?.asList()
        assertEquals(0, nestedListFromContext?.size)
    }
}