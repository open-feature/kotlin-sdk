package dev.openfeature.sdk

import dev.openfeature.sdk.helpers.BrokenInitProvider
import dev.openfeature.sdk.helpers.DoSomethingProvider
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class StatusTests {

    @After
    fun tearDown() {
        // It becomes important to clear the provider after each test since the SDK is a singleton
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
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("some value"))
        testScheduler.advanceUntilIdle()
        assertEquals(4, statuses.size)
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("some other value"))
        testScheduler.advanceUntilIdle()
        assertEquals(6, statuses.size)
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        job.cancelAndJoin()
        assertEquals(7, statuses.size)
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
    }
}