package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val ORDERING_ITERATIONS = 300
private const val TRACKER_ITERATIONS = 500
private const val EVENTS_PER_ITERATION = 8
private const val TIMEOUT_SECONDS = 5L

/**
 * The provider lifecycle and tracker contracts that need real threads: JS is single-threaded and
 * common test code cannot portably block.
 */
@OptIn(ExperimentalIsolatedApi::class, ExperimentalCoroutinesApi::class)
class ProviderConcurrencyTest {

    @AfterTest
    fun tearDown() {
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    private class OrderRecordingProvider(private val order: MutableList<String>) : NoOpProvider() {
        override suspend fun initialize(initialContext: EvaluationContext?) {
            order += "initialize"
            super.initialize(initialContext)
        }

        override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
            order += "onContextSet"
        }
    }

    private class ImmediateDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = false

        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()

        override fun limitedParallelism(parallelism: Int): CoroutineDispatcher = this
    }

    private class BlockingInitProvider(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : NoOpProvider() {
        override suspend fun initialize(initialContext: EvaluationContext?) {
            entered.countDown()
            release.await()
        }
    }

    private class BlockingShutdownProvider : NoOpProvider() {
        val shutdownEntered = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)

        override fun shutdown() {
            shutdownEntered.countDown()
            releaseShutdown.await(30, TimeUnit.SECONDS)
            super.shutdown()
        }
    }

    @Test
    fun aConcurrentContextSetNeverEntersBeforeInitializeOnTheSameRegistration() = runBlocking {
        repeat(ORDERING_ITERATIONS) { iteration ->
            val instance = createOpenFeatureAPIInstance()
            val order = CopyOnWriteArrayList<String>()
            val provider = OrderRecordingProvider(order)

            val setProviderJob = launch(Dispatchers.Default) { instance.setProvider(provider) }
            val setContextJob = launch(Dispatchers.Default) { instance.setEvaluationContext(ImmutableContext()) }
            setProviderJob.join()
            setContextJob.join()
            delay(20)

            val recorded = order.toList()
            if (recorded.contains("onContextSet")) {
                assertEquals(
                    "initialize",
                    recorded.first(),
                    "iteration $iteration observed onContextSet before initialize: $recorded"
                )
            }
        }
    }

    @Test
    fun initializeOnAnImmediateDispatcherDoesNotRunWithTheStateLockHeld() {
        val instance = createOpenFeatureAPIInstance()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = BlockingInitProvider(entered, release)
        val probe = Executors.newSingleThreadExecutor()

        val setter = thread { instance.setProvider(provider, ImmediateDispatcher()) }
        try {
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "initialize never ran")
            val observed = probe.submit<FeatureProvider> { instance.getProvider() }
            assertSame(provider, observed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            probe.shutdown()
            setter.join()
        }
    }

    @Test
    fun setProviderDoesNotBlockOnTheOutgoingProvidersShutdown() {
        val instance = createOpenFeatureAPIInstance()
        val outgoing = BlockingShutdownProvider()
        runBlocking { instance.setProviderAndWait(outgoing) }

        val returned = CountDownLatch(1)
        thread {
            instance.setProvider(NoOpProvider())
            returned.countDown()
        }

        try {
            assertTrue(
                returned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "setProvider blocked on the outgoing provider's shutdown"
            )
            assertTrue(
                outgoing.shutdownEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the outgoing provider was never shut down"
            )
        } finally {
            outgoing.releaseShutdown.countDown()
        }
    }

    @Test
    fun aProviderCannotBeRegisteredAgainUntilShutdownFinishes() = runBlocking {
        val instance = createOpenFeatureAPIInstance()
        val provider = BlockingShutdownProvider()
        instance.setProviderAndWait(provider)

        val replacement = NoOpProvider()
        val replacing = async(Dispatchers.Default) { instance.setProviderAndWait(replacement) }
        try {
            assertTrue(
                provider.shutdownEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the replaced provider was never retired"
            )
            assertFailsWith<IllegalStateException> {
                instance.setProvider(provider, initialContext = ImmutableContext("rejected"))
            }
            assertFailsWith<IllegalStateException> {
                instance.setProviderAndWait(provider)
            }
            assertSame(replacement, instance.getProvider())
            assertEquals(null, instance.getEvaluationContext())
        } finally {
            provider.releaseShutdown.countDown()
            replacing.await()
        }

        instance.setProviderAndWait(provider)
        assertSame(provider, instance.getProvider())
        assertEquals(OpenFeatureStatus.Ready, instance.getStatus())
        instance.clearProvider()
    }

    @Test
    fun setProviderAndWaitReportsTheOutgoingProviderDown() {
        val instance = createOpenFeatureAPIInstance()
        val outgoing = BlockingShutdownProvider()
        outgoing.releaseShutdown.countDown()
        runBlocking { instance.setProviderAndWait(outgoing) }

        runBlocking { instance.setProviderAndWait(NoOpProvider()) }

        assertEquals(0, outgoing.shutdownEntered.count)
    }

    @Test
    fun subscribingWhileEventsAreSentNeverDuplicatesOrReordersThem() = runBlocking {
        repeat(TRACKER_ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(errorNumbered(0))

            val observed = mutableListOf<Int>()
            val subscribed = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                tracker.observe().collect { event ->
                    subscribed.complete(Unit)
                    observed.add(event.number())
                }
            }

            launch(Dispatchers.Default) {
                for (number in 1..EVENTS_PER_ITERATION) tracker.send(errorNumbered(number))
            }.join()

            withTimeout(5_000) { subscribed.await() }
            collector.cancel()
            collector.join()

            val snapshot = observed.toList()
            assertTrue(
                snapshot.zipWithNext().all { (previous, next) -> previous < next },
                "iteration $iteration observed a duplicate or out-of-order sequence: $snapshot"
            )
        }
    }

    @Test
    fun statusIsConsistentWithTheLastEventSentUnderConcurrentSenders() = runBlocking {
        repeat(TRACKER_ITERATIONS) {
            val tracker = ProviderStatusTracker()
            val senders = (1..4).map {
                launch(Dispatchers.Default) {
                    repeat(EVENTS_PER_ITERATION) { tracker.send(OpenFeatureProviderEvents.ProviderStale()) }
                }
            }
            senders.forEach { it.join() }
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            assertEquals(OpenFeatureStatus.Ready, tracker.status)
        }
    }

    @Test
    fun racingReconciliationsNeverLeaveTheTrackerReconciling() = runBlocking {
        repeat(TRACKER_ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            (1..2).map { launch(Dispatchers.Default) { tracker.reconciling { } } }.forEach { it.join() }

            assertEquals(
                OpenFeatureStatus.Ready,
                tracker.status,
                "iteration $iteration was left mid-reconciliation"
            )
        }
    }

    @Test
    fun theReplayHandsOffToTheLiveStreamWithoutLoss() = runBlocking {
        repeat(TRACKER_ITERATIONS) { iteration ->
            val tracker = ProviderStatusTracker()
            tracker.send(OpenFeatureProviderEvents.ProviderReady())

            val observed = mutableListOf<OpenFeatureProviderEvents>()
            val replayed = CompletableDeferred<Unit>()
            val handedOff = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                tracker.observe().collect {
                    observed.add(it)
                    replayed.complete(Unit)
                    if (observed.size == 3) handedOff.complete(Unit)
                }
            }
            withTimeout(5_000) { replayed.await() }
            launch(Dispatchers.Default) {
                tracker.send(OpenFeatureProviderEvents.ProviderStale())
                tracker.send(OpenFeatureProviderEvents.ProviderReconciling())
            }.join()

            withTimeout(5_000) { handedOff.await() }
            collector.cancel()
            collector.join()

            assertEquals(
                listOf(
                    OpenFeatureProviderEvents.ProviderReady::class,
                    OpenFeatureProviderEvents.ProviderStale::class,
                    OpenFeatureProviderEvents.ProviderReconciling::class
                ),
                observed.take(3).map { it::class },
                "iteration $iteration"
            )
        }
    }

    private fun errorNumbered(number: Int) = OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(message = number.toString())
    )

    private fun OpenFeatureProviderEvents.number(): Int =
        requireNotNull(eventDetails?.message) { "event carried no number" }.toInt()
}