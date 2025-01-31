package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode
import dev.openfeature.sdk.exceptions.OpenFeatureError
import dev.openfeature.sdk.helpers.AutoHealingProvider
import dev.openfeature.sdk.helpers.BrokenInitProvider
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import dev.openfeature.sdk.helpers.SlowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@ExperimentalCoroutinesApi
class DeveloperExperienceTests {

    @After
    fun tearDown() {
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
        Assert.assertFalse(booleanValue)
    }

    @Test
    fun testSetProviderWithDefaultDispatcher() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProvider(
            SlowProvider(dispatcher = testDispatcher),
            initialContext = ImmutableContext()
        )
        OpenFeatureAPI.statusFlow.firstOrNull { it is OpenFeatureStatus.Ready }
        val booleanDetails = OpenFeatureAPI.getClient().getBooleanDetails("test", false)
        Assert.assertNull(booleanDetails.errorCode)
        Assert.assertNull(booleanDetails.errorMessage)
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
        Assert.assertNull(booleanDetails.errorCode)
        Assert.assertNull(booleanDetails.errorMessage)
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
        val client = OpenFeatureAPI.getClient()

        val details = client.getBooleanDetails("test", false)
        assertEquals(ErrorCode.FLAG_NOT_FOUND, details.errorCode)
        assertEquals("Could not find flag named: test", details.errorMessage)
        assertEquals(Reason.ERROR.toString(), details.reason)
    }

    @Test
    fun testSetProviderAndWaitReady() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        CoroutineScope(dispatcher).launch {
            OpenFeatureAPI.setProviderAndWait(
                SlowProvider(dispatcher = dispatcher),
                ImmutableContext()
            )
        }
        testScheduler.advanceTimeBy(1) // Make sure setProviderAndWait is called
        val booleanValue1 = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        Assert.assertFalse(booleanValue1)
        testScheduler.advanceTimeBy(10000) // SlowProvider is now Ready
        val booleanValue2 = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        assertTrue(booleanValue2)
    }

    @Test
    fun testSetProviderAndWaitError() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider(), ImmutableContext())
        val booleanValue = OpenFeatureAPI.getClient().getBooleanValue("test", false)
        Assert.assertFalse(booleanValue)
    }

    @Test
    fun testStatusFlow() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val emittedStatuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                emittedStatuses.add(it)
            }
        }
        OpenFeatureAPI.clearProvider()
        testScheduler.advanceUntilIdle()
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
    fun testProviderThatHealsWithErrorThenReady() = runTest {
        val healDelayMillis: Long = 100
        val healing = AutoHealingProvider(healDelay = healDelayMillis)
        val job = async {
            OpenFeatureAPI.setProviderAndWait(healing, ImmutableContext())
        }
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        testScheduler.advanceTimeBy(1)
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        testScheduler.advanceTimeBy(healDelayMillis)
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        job.cancelAndJoin()
        OpenFeatureAPI.shutdown()
    }
}