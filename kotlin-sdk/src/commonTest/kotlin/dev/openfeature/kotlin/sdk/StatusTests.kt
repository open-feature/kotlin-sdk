package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.SlowProvider
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun testContextSetAfterClearProviderRemainsNotReady() = runTest {
        val context = ImmutableContext("same-context")
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        OpenFeatureAPI.setEvaluationContextAndWait(context)
        OpenFeatureAPI.clearProvider()

        OpenFeatureAPI.setEvaluationContextAndWait(context)

        assertTrue(OpenFeatureAPI.getEvaluationContext() === context)
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
    fun testStatusRemainsReconcilingUntilAllEqualContextSetsComplete() = runTest {
        val context = ImmutableContext("same-context")
        val provider = ControllableContextProvider()
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))

        val firstContextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(context)
        }
        provider.contextSetStarted.receive()
        val secondContextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(context)
        }
        provider.contextSetStarted.receive()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        provider.allowContextSetToComplete.send(Unit)
        provider.contextSetCompleted.receive()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        provider.allowContextSetToComplete.send(Unit)
        firstContextSet.join()
        secondContextSet.join()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testCancelledContextSetRestoresPreviousStatus() = runTest {
        val provider = ControllableContextProvider()
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))

        val contextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("cancelled"))
        }
        provider.contextSetStarted.receive()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        contextSet.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCancelledContextSetFinishingLastUsesReplacementStatus() = runTest {
        val provider = CancellationRaceProvider()
        val dispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = dispatcher)

        OpenFeatureAPI.setEvaluationContext(ImmutableContext("first"), dispatcher)
        provider.firstContextSetStarted.receive()
        OpenFeatureAPI.setEvaluationContext(ImmutableContext("replacement"), dispatcher)
        provider.firstContextSetCancellationStarted.receive()
        provider.replacementContextSetCompleted.receive()
        runCurrent()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        provider.allowFirstContextSetToFinish.send(Unit)
        advanceUntilIdle()

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testProviderEventAfterReplacementCompletionWinsOverRetainedStatus() = runTest {
        val provider = CancellationRaceProvider()
        val dispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = dispatcher)
        runCurrent()

        OpenFeatureAPI.setEvaluationContext(ImmutableContext("first"), dispatcher)
        provider.firstContextSetStarted.receive()
        OpenFeatureAPI.setEvaluationContext(ImmutableContext("replacement"), dispatcher)
        provider.firstContextSetCancellationStarted.receive()
        provider.replacementContextSetCompleted.receive()
        runCurrent()

        provider.emitStale()
        runCurrent()
        assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus())

        provider.allowFirstContextSetToFinish.send(Unit)
        advanceUntilIdle()

        assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testRetiredProviderReconciliationDoesNotOverwriteReplacementStatus() = runTest {
        val retiredProvider = ControllableContextProvider()
        val replacementProvider = CancellationRaceProvider()
        val dispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(retiredProvider, dispatcher = dispatcher)

        val retiredContextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("retired"))
        }
        retiredProvider.contextSetStarted.receive()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        OpenFeatureAPI.setProviderAndWait(replacementProvider, dispatcher = dispatcher)
        runCurrent()
        replacementProvider.emitStale()
        runCurrent()
        assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus())

        retiredProvider.allowContextSetToComplete.send(Unit)
        retiredContextSet.join()
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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

        // Advance the test scheduler to process all pending operations
        advanceUntilIdle()

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
        assertEquals(0, provider1.shutdownCalls.value)

        OpenFeatureAPI.setProviderAndWait(provider2)
        assertEquals(1, provider1.shutdownCalls.value)
        assertEquals(0, provider2.shutdownCalls.value)
    }

    @Test
    fun testMultipleProviderReplacements() = runTest {
        val provider1 = SpyProvider()
        val provider2 = SpyProvider()
        val provider3 = SpyProvider()

        OpenFeatureAPI.setProviderAndWait(provider1)
        assertEquals(0, provider1.shutdownCalls.value)

        OpenFeatureAPI.setProviderAndWait(provider2)
        assertEquals(1, provider1.shutdownCalls.value)
        assertEquals(0, provider2.shutdownCalls.value)

        OpenFeatureAPI.setProviderAndWait(provider3)
        assertEquals(1, provider1.shutdownCalls.value)
        assertEquals(1, provider2.shutdownCalls.value)
        assertEquals(0, provider3.shutdownCalls.value)
    }

    @Test
    fun testShutdownCalledWithSetProviderAsync() = runTest {
        val provider1 = SpyProvider()
        val provider2 = SpyProvider()

        OpenFeatureAPI.setProvider(provider1)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        assertEquals(0, provider1.shutdownCalls.value)

        OpenFeatureAPI.setProvider(provider2)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        // Use waitAssert for shutdown calls to handle timing differences across platforms
        waitAssert { assertEquals(1, provider1.shutdownCalls.value) }
        assertEquals(0, provider2.shutdownCalls.value)
    }
}

private class ControllableContextProvider : NoOpProvider() {
    val contextSetStarted = Channel<Unit>(Channel.UNLIMITED)
    val allowContextSetToComplete = Channel<Unit>(Channel.UNLIMITED)
    val contextSetCompleted = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        contextSetStarted.send(Unit)
        allowContextSetToComplete.receive()
        contextSetCompleted.send(Unit)
    }
}

private class CancellationRaceProvider : NoOpProvider() {
    val firstContextSetStarted = Channel<Unit>(Channel.UNLIMITED)
    val firstContextSetCancellationStarted = Channel<Unit>(Channel.UNLIMITED)
    val allowFirstContextSetToFinish = Channel<Unit>(Channel.UNLIMITED)
    val replacementContextSetCompleted = Channel<Unit>(Channel.UNLIMITED)

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = 1)
    private var contextSetCalls = 0

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    fun emitStale() {
        events.tryEmit(OpenFeatureProviderEvents.ProviderStale())
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        contextSetCalls++
        if (contextSetCalls == 1) {
            firstContextSetStarted.send(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    firstContextSetCancellationStarted.send(Unit)
                    allowFirstContextSetToFinish.receive()
                }
            }
        } else {
            replacementContextSetCompleted.send(Unit)
        }
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