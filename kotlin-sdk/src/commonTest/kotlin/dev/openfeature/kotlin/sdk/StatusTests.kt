package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.SlowProvider
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class StatusTests {

    @BeforeTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testNoProviderSet() {
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testProviderTransitionsToReadyAndNotReadyAfterShutdown() = runTest {
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        OpenFeatureAPI.shutdown()
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testProviderThrowsDuringInit() = runTest {
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        assertTrue(OpenFeatureAPI.getStatus() is OpenFeatureStatus.Error)
        OpenFeatureAPI.shutdown()
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testClearProviderEmitsNotReady() = runTest {
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        OpenFeatureAPI.clearProvider()
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testProviderTransitionsToReconcilingOnContextSet() = runTest {
        waitAssert {
            assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        }
        val statuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                statuses.add(it)
            }
        }
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("some value"))
        waitAssert { assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus()) }
        waitAssert {
            assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        }
        job.cancelAndJoin()
    }

    @Test
    fun testSpamSetContextWithoutAwait() = runTest {
        waitAssert {
            assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        }
        val statuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                statuses.add(it)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(SlowProvider(dispatcher = dispatcher))
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        for (i in 1..30) {
            OpenFeatureAPI.setEvaluationContext(ImmutableContext("test_$i"))
            delay(Duration.randomMs(0, 10))
        }

        waitAssert {
            assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        }
        assertFalse(statuses.any { it is OpenFeatureStatus.Error })
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        job.cancelAndJoin()
    }

    @Test
    fun testShutdownCalledWhenReplacingProvider() = runTest {
        val provider1 = SpyProvider()
        val provider2 = SpyProvider()

        OpenFeatureAPI.setProviderAndWait(provider1)
        assertEquals(0, provider1.shutdownCalls)

        OpenFeatureAPI.setProviderAndWait(provider2)
        assertEquals(1, provider1.shutdownCalls)
        assertEquals(0, provider2.shutdownCalls)
    }

    @Test
    fun testMultipleProviderReplacements() = runTest {
        val provider1 = SpyProvider()
        val provider2 = SpyProvider()
        val provider3 = SpyProvider()

        OpenFeatureAPI.setProviderAndWait(provider1)
        assertEquals(0, provider1.shutdownCalls)

        OpenFeatureAPI.setProviderAndWait(provider2)
        assertEquals(1, provider1.shutdownCalls)
        assertEquals(0, provider2.shutdownCalls)

        OpenFeatureAPI.setProviderAndWait(provider3)
        assertEquals(1, provider1.shutdownCalls)
        assertEquals(1, provider2.shutdownCalls)
        assertEquals(0, provider3.shutdownCalls)
    }

    @Test
    fun testShutdownCalledWithSetProviderAsync() = runTest {
        val provider1 = SpyProvider()
        val provider2 = SpyProvider()

        OpenFeatureAPI.setProvider(provider1)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        assertEquals(0, provider1.shutdownCalls)

        OpenFeatureAPI.setProvider(provider2)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        // Use waitAssert for shutdown calls to handle timing differences across platforms
        waitAssert { assertEquals(1, provider1.shutdownCalls) }
        assertEquals(0, provider2.shutdownCalls)
    }
}

private fun Duration.Companion.randomMs(min: Int, max: Int): Duration = Random.nextInt(min, max + 1).milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.waitAssert(timeoutMs: Long = 5000, function: () -> Unit) {
    var timeWaited = 0L
    while (timeWaited < timeoutMs) {
        try {
            function()
            return
        } catch (e: Throwable) {
            delay(10)
            timeWaited += 10
            advanceUntilIdle()
        }
    }
}