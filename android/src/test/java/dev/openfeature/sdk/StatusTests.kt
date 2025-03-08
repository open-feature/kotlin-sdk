package dev.openfeature.sdk

import dev.openfeature.sdk.helpers.BrokenInitProvider
import dev.openfeature.sdk.helpers.DoSomethingProvider
import dev.openfeature.sdk.helpers.SlowProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration

class StatusTests {

    @Before
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
        statuses.forEach {
            print(it)
            (it as? OpenFeatureStatus.Error)?.error?.let {
                print(" ${it.errorCode()}")
                print(" ${it.message}")
            }
            println()
        }
        assertFalse(statuses.any { it is OpenFeatureStatus.Error })
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        job.cancelAndJoin()
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