package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.SlowProvider
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
        advanceUntilIdle()
        job.cancelAndJoin()

        // Reconciling is transient: it has already been superseded by the time the call returns, so
        // it can only be observed in the collected sequence, not by polling getStatus().
        assertTrue(
            statuses.contains(OpenFeatureStatus.Reconciling),
            "expected a Reconciling transition, collected $statuses"
        )
        assertEquals(OpenFeatureStatus.Ready, statuses.last())
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testStatusRemainsReconcilingUntilAllEqualContextSetsComplete() = runTest {
        val context = ImmutableContext("same-context")
        val provider = ControllableContextProvider()
        OpenFeatureAPI.setProviderAndWait(provider)

        val firstContextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(context)
        }
        provider.contextSetStarted.receive()
        val secondContextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(context)
        }
        provider.contextSetStarted.receive()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        provider.allowContextSetToComplete.send(Unit)
        provider.contextSetCompleted.receive()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        provider.allowContextSetToComplete.send(Unit)
        firstContextSet.join()
        secondContextSet.join()
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testCancelledContextSetRestoresPreviousStatus() = runTest {
        val provider = ControllableContextProvider()
        OpenFeatureAPI.setProviderAndWait(provider)

        val contextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("cancelled"))
        }
        provider.contextSetStarted.receive()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        contextSet.cancelAndJoin()

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCancelledContextSetFinishingLastUsesReplacementStatus() = runTest {
        val provider = CancellationRaceProvider()
        val dispatcher = StandardTestDispatcher(testScheduler)
        // The registration's dispatcher is what runs its reconciliations, so virtual time needs it.
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
        OpenFeatureAPI.setProviderAndWait(retiredProvider)

        val retiredContextSet = launch {
            OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("retired"))
        }
        retiredProvider.contextSetStarted.receive()
        assertEquals(OpenFeatureStatus.Reconciling, OpenFeatureAPI.getStatus())

        OpenFeatureAPI.setProviderAndWait(replacementProvider, dispatcher = dispatcher)
        runCurrent()
        replacementProvider.emitStale()
        runCurrent()
        assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus())

        retiredProvider.allowContextSetToComplete.send(Unit)
        retiredContextSet.join()

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
    private val statusTracker = ProviderStatusTracker()

    val contextSetStarted = Channel<Unit>(Channel.UNLIMITED)
    val allowContextSetToComplete = Channel<Unit>(Channel.UNLIMITED)
    val contextSetCompleted = Channel<Unit>(Channel.UNLIMITED)

    override val status: OpenFeatureStatus get() = statusTracker.status

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) = statusTracker.reconciling {
        contextSetStarted.send(Unit)
        allowContextSetToComplete.receive()
        contextSetCompleted.send(Unit)
    }

    override fun shutdown() = statusTracker.reset()
}

private class CancellationRaceProvider : NoOpProvider() {
    val firstContextSetStarted = Channel<Unit>(Channel.UNLIMITED)
    val firstContextSetCancellationStarted = Channel<Unit>(Channel.UNLIMITED)
    val allowFirstContextSetToFinish = Channel<Unit>(Channel.UNLIMITED)
    val replacementContextSetCompleted = Channel<Unit>(Channel.UNLIMITED)

    private val statusTracker = ProviderStatusTracker()
    private var contextSetCalls = 0

    override val status: OpenFeatureStatus get() = statusTracker.status

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() = statusTracker.reset()

    fun emitStale() {
        statusTracker.send(OpenFeatureProviderEvents.ProviderStale())
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) = statusTracker.reconciling {
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

/** Retries [function] until it passes, rethrowing its last failure once [timeoutMs] is exhausted. */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.waitAssert(timeoutMs: Long = 5000, function: () -> Unit) {
    var timeWaited = 0L
    while (true) {
        try {
            function()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (timeWaited >= timeoutMs) throw e
            delay(10)
            timeWaited += 10
            advanceUntilIdle()
        }
    }
}