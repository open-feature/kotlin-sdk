package dev.openfeature.sdk

import dev.openfeature.sdk.helpers.BrokenInitProvider
import dev.openfeature.sdk.helpers.DoSomethingProvider
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

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
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        val statuses = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect {
                statuses.add(it)
            }
        }
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("some value"))
        testScheduler.advanceUntilIdle()
        waitAssert {
            assertEquals(4, statuses.size)
        }

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("some other value"))
        testScheduler.advanceUntilIdle()
        waitAssert {
            assertEquals(6, statuses.size)
        }

        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        waitAssert {
            assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        }
        assertEquals(
            listOf(
                OpenFeatureStatus.NotReady,
                OpenFeatureStatus.Ready,
                OpenFeatureStatus.Reconciling,
                OpenFeatureStatus.Ready,
                OpenFeatureStatus.Reconciling,
                OpenFeatureStatus.Ready,
                OpenFeatureStatus.NotReady
            ),
            statuses
        )
        job.cancelAndJoin()
    }
}

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