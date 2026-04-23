package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.OverlyEmittingProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderEventingTests {

    @BeforeTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testProviderThatErrorsAndThenSendsConfigurationChanged() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val healDelayMillis = 1000L
        val provider = object : DoSomethingProvider() {
            val flow = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)
            override suspend fun initialize(initialContext: EvaluationContext?) {
                // no-op
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
                flow.emit(
                    OpenFeatureProviderEvents.ProviderError(
                        error = OpenFeatureError.ProviderNotReadyError(
                            "test error"
                        )
                    )
                )
                delay(healDelayMillis)
                flow.emit(
                    OpenFeatureProviderEvents.ProviderConfigurationChanged()
                )
            }

            override fun observe(): Flow<OpenFeatureProviderEvents> = flow
        }
        val statusList = mutableListOf<OpenFeatureStatus>()
        val j = async(testDispatcher) {
            OpenFeatureAPI.statusFlow.toCollection(statusList)
        }

        OpenFeatureAPI.setProviderAndWait(
            provider,
            dispatcher = testDispatcher,
            initialContext = ImmutableContext()
        )
        testScheduler.advanceUntilIdle()
        waitAssert {
            assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        }
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("new"))
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        j.cancelAndJoin()
        waitAssert {
            assertEquals(5, statusList.size)
        }
        assertEquals(OpenFeatureStatus.Ready, statusList[0])
        assertEquals(OpenFeatureStatus.Reconciling, statusList[1])
        assertTrue(statusList[2] is OpenFeatureStatus.Error)
        assertEquals(OpenFeatureStatus.Ready, statusList[3])
        assertEquals(OpenFeatureStatus.NotReady, statusList[4])
    }

    @Test
    fun testProviderEventFlowShouldSupportSwappingProviders() = runTest {
        val firstProvider = OverlyEmittingProvider("First Provider")
        val secondProvider = OverlyEmittingProvider("Second Provider")

        val emittedEvents = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect {
                emittedEvents.add(it)
            }
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)

        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            firstProvider,
            initialContext = ImmutableContext("first"),
            dispatcher = testDispatcher
        )
        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(
            ImmutableContext("first.v2")
        )
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderReconciling(),
                OpenFeatureProviderEvents.ProviderContextChanged(),
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged()
            ),
            emittedEvents
        )
        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            secondProvider,
            initialContext = ImmutableContext("second"),
            dispatcher = testDispatcher
        )
        testScheduler.advanceUntilIdle()
        // emits ProviderStale + ProviderStale + ProviderStale
        OpenFeatureAPI.getClient().track("hello-world")
        testScheduler.advanceUntilIdle()

        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(
            ImmutableContext("second.v2")
        )
        testScheduler.advanceUntilIdle()

        OpenFeatureAPI.shutdown()
        job.cancelAndJoin()
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderReconciling(),
                OpenFeatureProviderEvents.ProviderContextChanged(),
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                // Second provider events:
                OpenFeatureProviderEvents.ProviderReady(), // SDK init (onMultiProvider with second provider
                OpenFeatureProviderEvents.ProviderReady(), // Provider background init (flushed by advanceUntilIdle)
                OpenFeatureProviderEvents.ProviderStale(), // track background flushed
                OpenFeatureProviderEvents.ProviderStale(), // track background flushed
                OpenFeatureProviderEvents.ProviderStale(), // track background flushed
                OpenFeatureProviderEvents.ProviderReconciling(), // SDK pre-context
                OpenFeatureProviderEvents.ProviderContextChanged(), // SDK post-context
                OpenFeatureProviderEvents.ProviderStale(), // onContextSet background flushed
                OpenFeatureProviderEvents.ProviderConfigurationChanged() // onContextSet background flushed
            ),
            emittedEvents
        )
    }

    @Test
    fun testProviderOnContextSetThrowsExceptionEmitsErrorEvent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = object : DoSomethingProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                // no-op
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
                throw IllegalStateException("Intentional crash during reconciliation")
            }
        }

        val emittedEvents = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch(testDispatcher) {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect {
                emittedEvents.add(it)
            }
        }

        // 1. Set the provider, wait for initialize
        OpenFeatureAPI.setProviderAndWait(
            provider,
            dispatcher = testDispatcher,
            initialContext = ImmutableContext()
        )
        testScheduler.advanceUntilIdle()

        emittedEvents.clear() // clear the ProviderReady event from init

        // 2. Set evaluation context, which triggers onContextSet and throws
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("new-context"))
        testScheduler.advanceUntilIdle()

        // 3. Verify exactly Reconciling followed by Error (no ContextChanged)
        assertEquals(2, emittedEvents.size)
        assertEquals(OpenFeatureProviderEvents.ProviderReconciling(), emittedEvents[0])
        assertTrue(emittedEvents[1] is OpenFeatureProviderEvents.ProviderError)
        val errorEvent = emittedEvents[1] as OpenFeatureProviderEvents.ProviderError
        assertEquals("Intentional crash during reconciliation", errorEvent.eventDetails?.message)

        job.cancelAndJoin()
    }

    @Test
    fun testSharedProviderIsNotShutdownUntilLastDomainIsCleared() = runTest {
        var shutdownCalls = 0
        val sharedProvider = object : DoSomethingProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                // no-op
            }
            override fun shutdown() {
                shutdownCalls++
            }
        }

        // Bind shared provider to domain A
        OpenFeatureAPI.setProviderAndWait("domainA", sharedProvider, dispatcher = StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // Bind shared provider to domain B
        OpenFeatureAPI.setProviderAndWait("domainB", sharedProvider, dispatcher = StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertEquals(0, shutdownCalls, "Should not be shut down yet")

        // Swap provider on domain A
        val newProvider = DoSomethingProvider()
        OpenFeatureAPI.setProviderAndWait("domainA", newProvider, dispatcher = StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        // Verify sharedProvider was NOT shut down because it's still bound to domain B
        assertEquals(0, shutdownCalls, "Should not be shut down since domain B still uses it")

        // Clear all providers (including domain B)
        OpenFeatureAPI.clearProvider()
        testScheduler.advanceUntilIdle()

        // Now it should be shut down exactly once
        assertEquals(1, shutdownCalls, "Should be shut down exactly once after all bindings are removed")
    }

    @Test
    fun testSharedProviderIsInitializedOnlyOnce() = runTest {
        var initializeCalls = 0
        val sharedProvider = object : DoSomethingProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                initializeCalls++
                delay(10) // Simulate some work
            }
        }

        val jobA = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainA",
                sharedProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }

        val jobB = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainB",
                sharedProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }

        testScheduler.advanceUntilIdle()

        jobA.join()
        jobB.join()

        assertEquals(1, initializeCalls, "Should be initialized exactly once globally")

        val clientA = OpenFeatureAPI.getClient("domainA")
        val clientB = OpenFeatureAPI.getClient("domainB")

        assertEquals(
            OpenFeatureStatus.Ready,
            OpenFeatureAPI.getProviderStatus("domainA")
        )
        assertEquals(
            OpenFeatureStatus.Ready,
            OpenFeatureAPI.getProviderStatus("domainB")
        )
    }

    @Test
    fun testSharedProviderSyncsErrorState() = runTest {
        val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 0, extraBufferCapacity = 1)
        val sharedProvider = object : DoSomethingProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                delay(10)
            }
            override fun observe(): Flow<OpenFeatureProviderEvents> = eventFlow
        }

        // Domain A binds and initializes the provider
        val jobA = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainA",
                sharedProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }
        testScheduler.advanceUntilIdle()
        jobA.join()

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getProviderStatus("domainA"))

        // Simulate a crash emitting an Error event natively from the provider
        eventFlow.emit(
            OpenFeatureProviderEvents.ProviderError(
                dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.EventDetails(message = "Simulated Crash")
            )
        )
        testScheduler.advanceUntilIdle()

        // Domain A should now reflect the Error status
        val statusA = OpenFeatureAPI.getProviderStatus("domainA")
        assertTrue(statusA is OpenFeatureStatus.Error)

        // Domain B now binds the SAME provider, bypassing initialization
        val jobB = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainB",
                sharedProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }
        testScheduler.advanceUntilIdle()
        jobB.join()

        // Domain B should correctly sync the Error state, NOT falsely emit Ready
        val statusB = OpenFeatureAPI.getProviderStatus("domainB")
        assertTrue(statusB is OpenFeatureStatus.Error)
    }

    /**
     * Verifies that when a domain binds to an already initialized shared provider, it successfully
     * bypasses the redundant `initialize()` call, syncs the global status from the provider,
     * AND synthetically broadcasts the corresponding event (e.g. ProviderReady) to its
     * locally bound event streams so that clients observing events receive the latest state.
     */
    @Test
    fun testSharedProviderEmitsSyntheticEventsOnBypass() = runTest {
        val sharedProvider = object : DoSomethingProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                delay(10)
            }
        }

        // Domain A initializes the provider
        val jobA = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainA",
                sharedProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }
        testScheduler.advanceUntilIdle()
        jobA.join()

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getProviderStatus("domainA"))

        // Pre-initialize Domain B with a NoOpProvider so the state is created and the flow is wired up
        val jobPreB = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainB",
                NoOpProvider(),
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }
        testScheduler.advanceUntilIdle()
        jobPreB.join()

        // Set up Domain B's client and listen to events
        val clientB = OpenFeatureAPI.getClient("domainB")
        val eventsReceived = mutableListOf<OpenFeatureProviderEvents>()

        val eventJob = launch(StandardTestDispatcher(testScheduler)) {
            clientB.observeEvents().collect { event ->
                eventsReceived.add(event)
            }
        }
        testScheduler.advanceUntilIdle() // Ensure collection starts

        // Domain B binds the provider, bypassing initialization
        val jobB = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setProviderAndWait(
                "domainB",
                sharedProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }

        testScheduler.advanceUntilIdle()
        jobB.join()

        // Verify the synthetic Ready event was broadcasted to Domain B's clients
        assertTrue(
            eventsReceived.any { it is OpenFeatureProviderEvents.ProviderReady },
            "Domain B should have received a synthetic ProviderReady event"
        )

        eventJob.cancel()
    }
}