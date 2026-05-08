package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderRepositoryTest {

    @Test
    fun `getOrCreateState should return default state when domain is null`() = runTest {
        val repository = ProviderRepository()

        val defaultState = repository.getOrCreateState(null)
        val defaultStateFromGet = repository.getState(null)

        assertSame(defaultState, defaultStateFromGet)
    }

    @Test
    fun `getOrCreateState should isolate domain states`() = runTest {
        val repository = ProviderRepository()

        val defaultState = repository.getOrCreateState(null)
        val domainStateA = repository.getOrCreateState("domainA")
        val domainStateB = repository.getOrCreateState("domainB")

        assertNotEquals(defaultState, domainStateA)
        assertNotEquals(domainStateA, domainStateB)

        assertSame(domainStateA, repository.getState("domainA"))
        assertSame(domainStateB, repository.getState("domainB"))
    }

    @Test
    fun `getAllStates should reflect created domains`() = runTest {
        val repository = ProviderRepository()

        assertEquals(1, repository.getAllStates().size) // Default state

        repository.getOrCreateState("domainX")
        repository.getOrCreateState("domainY")

        assertEquals(3, repository.getAllStates().size)
    }

    @Test
    fun `getStateFlow should track newly created domains`() = runTest {
        val repository = ProviderRepository()

        val flow = repository.getStateFlow("dynamicDomain")

        // Before creation, it yields the default state fallback logic
        val defaultMappedState = flow.first()
        assertSame(repository.getState(null), defaultMappedState)

        // Dynamically create the state
        val dynamicState = repository.getOrCreateState("dynamicDomain")

        val newMappedState = flow.first()
        assertSame(dynamicState, newMappedState)
    }

    @Test
    fun `getStateFlow should memoize null domain state flow`() = runTest {
        val repository = ProviderRepository()

        val firstFlow = repository.getStateFlow(null)
        val secondFlow = repository.getStateFlow(null)

        assertSame(firstFlow, secondFlow)
        assertSame(repository.defaultStateFlow, firstFlow)

        val nonNullFlow = repository.getStateFlow("specific-domain")
        assertNotSame(repository.defaultStateFlow, nonNullFlow)
    }

    @Test
    fun `getOrCreateState should be thread-safe for concurrent creation`() = runTest {
        val repository = ProviderRepository()

        val jobs = List(100) {
            launch {
                repository.getOrCreateState("concurrentDomain")
            }
        }

        // Wait for all 100 concurrent creation jobs to finish
        jobs.forEach { it.join() }

        // Even with 100 concurrent jobs racing to create the domain,
        // there should only be 1 total state per domain inside the repository (Default + concurrentDomain = 2)
        assertEquals(2, repository.getAllStates().size)
    }

    @Test
    fun `clearAll should recreate empty map and isolate future calls`() = runTest {
        val repository = ProviderRepository()

        val domainA = repository.getOrCreateState("domainA")

        assertEquals(2, repository.getAllStates().size)

        repository.clearAll()

        // Ensure ONLY the default state object remains
        assertEquals(1, repository.getAllStates().size)

        // Subsequent creation should yield a NEW distinct state reference since the old one was removed
        val newDomainA = repository.getOrCreateState("domainA")
        assertNotEquals(domainA, newDomainA)
    }

    @Test
    fun `clearAll should successfully terminate array even if provider shutdown throws`() = runTest {
        val repository = ProviderRepository()

        val stateA = repository.getOrCreateState("domainA")
        val stateB = repository.getOrCreateState("domainB")

        val explosiveProvider = object : FeatureProvider by NoOpProvider() {
            override fun shutdown() {
                throw RuntimeException("Simulated hostile provider shutdown crash")
            }
        }

        stateA.providersFlow.value = explosiveProvider

        // If clearAll crashed upon iterating stateA, stateB wouldn't successfully transition to NotReady securely.
        repository.clearAll()

        // Assert BOTH safely resolved their inner _statusFlow sequences
        assertEquals(OpenFeatureStatus.NotReady, stateA.getStatus())
        assertEquals(OpenFeatureStatus.NotReady, stateB.getStatus())

        // Assert structure correctly purged isolated arrays unconditionally
        assertEquals(1, repository.getAllStates().size)
    }

    @Test
    fun `DomainState should automatically cancel old provider event listeners on swap`() = runTest {
        val oldProviderEvents = MutableSharedFlow<OpenFeatureProviderEvents>()
        val newProviderEvents = MutableSharedFlow<OpenFeatureProviderEvents>()

        class MockEventProvider(
            val source: MutableSharedFlow<OpenFeatureProviderEvents>
        ) : FeatureProvider by NoOpProvider() {
            override fun observe() = source
        }

        val oldProvider = MockEventProvider(oldProviderEvents)
        val newProvider = MockEventProvider(newProviderEvents)

        val state = DomainState()
        state.initializeListener(StandardTestDispatcher(testScheduler))

        // 1. Assign the old provider
        state.providersFlow.value = oldProvider

        // Wait for collectLatest coroutine to subscribe
        testScheduler.advanceUntilIdle()

        // 2. Emit an event from the old provider
        oldProviderEvents.emit(OpenFeatureProviderEvents.ProviderReady())
        testScheduler.advanceUntilIdle()

        // Assert it was successfully mapped and propagated
        assertEquals(OpenFeatureStatus.Ready, state.getStatus())

        // 3. Swap in the totally new provider
        state.providersFlow.value = newProvider
        testScheduler.advanceUntilIdle()

        // 4. Fire a hostile error event from the OLD, strictly-abandoned provider
        oldProviderEvents.emit(
            OpenFeatureProviderEvents.ProviderError(error = OpenFeatureError.ProviderNotReadyError())
        )
        testScheduler.advanceUntilIdle()

        // Flow conflation and auto-cancellation guarantees that the old provider error strictly gets ignored
        assertEquals(OpenFeatureStatus.Ready, state.getStatus())

        // 5. Fire an event from the new provider to prove the listener was properly hot-swapped
        newProviderEvents.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()

        // Verify the newly mapped listener successfully overrides state
        assertEquals(OpenFeatureStatus.Stale, state.getStatus())
    }

    @Test
    fun `DomainState should restart event listener if dispatcher changes`() = runTest {
        val state = DomainState()

        val dispatcher1 = StandardTestDispatcher(testScheduler)
        val dispatcher2 = StandardTestDispatcher(testScheduler)

        val providerEvents = MutableSharedFlow<OpenFeatureProviderEvents>()
        class MockEventProvider : FeatureProvider by NoOpProvider() {
            override fun observe() = providerEvents
        }
        val provider = MockEventProvider()
        state.providersFlow.value = provider

        // Step 1: Initialize with dispatcher1
        state.initializeListener(dispatcher1)
        testScheduler.advanceUntilIdle()

        // Emit event to verify dispatcher1 listener is working
        providerEvents.emit(OpenFeatureProviderEvents.ProviderReady())
        testScheduler.advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Ready, state.getStatus())

        // Step 2: Initialize with the EXACT SAME dispatcher, shouldn't disrupt anything
        state.initializeListener(dispatcher1)
        testScheduler.advanceUntilIdle()

        // Step 3: Initialize with a DIFFERENT dispatcher (dispatcher2)
        state.initializeListener(dispatcher2)
        testScheduler.advanceUntilIdle()

        // Fire another event, it should be processed by the NEW listener that was just hot-swapped
        providerEvents.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Stale, state.getStatus())
    }

    @Test
    fun `DomainState should automatically retry and emit error on provider unhandled exception`() = runTest {
        val state = DomainState()
        val errorMsg = "Simulated internal crash"
        var observeCallCount = 0

        class UnstableProvider : FeatureProvider by NoOpProvider() {
            override fun observe(): kotlinx.coroutines.flow.Flow<OpenFeatureProviderEvents> = flow {
                observeCallCount++
                throw RuntimeException(errorMsg)
            }
        }

        state.providersFlow.value = UnstableProvider()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        state.initializeListener(testDispatcher)

        testScheduler.advanceTimeBy(100L) // Process first crash without entering infinite loop

        val status = state.getStatus()
        assertTrue(status is OpenFeatureStatus.Error)
        assertEquals(errorMsg, status.error.message)
        assertEquals(1, observeCallCount)

        // Then, advance by 3000ms. It should trigger retryWhen delay and retry
        testScheduler.advanceTimeBy(3500L)
        // Ensure it re-observed!
        assertEquals(2, observeCallCount)

        // Cancel scope explicitly to avoid hanging the `runTest` finalizer loop
        state.resetAndGetProvider()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `DomainState should drop oldest status seamlessly and avoid suspending backpressure when blasted`() = runTest {
        val state = DomainState()
        val eventsFlow = MutableSharedFlow<OpenFeatureProviderEvents>()

        class SpammyProvider : FeatureProvider by NoOpProvider() {
            override fun observe() = eventsFlow
        }

        state.providersFlow.value = SpammyProvider()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        state.initializeListener(testDispatcher)
        testScheduler.advanceUntilIdle()

        // Mimic a slow processor that purposefully does not collect from statusFlow.
        val slowSubscriber = launch(StandardTestDispatcher(testScheduler)) {
            state.statusFlow.collect {
                delay(10000L) // Extremely slow
            }
        }
        testScheduler.advanceUntilIdle()

        // Blast 50 items simultaneously into eventsFlow -> emitStatus.
        // If it was BufferOverflow.SUSPEND this wouldn't finish.
        val job = launch(StandardTestDispatcher(testScheduler)) {
            for (i in 1..50) {
                eventsFlow.emit(
                    if (i % 2 == 0) {
                        OpenFeatureProviderEvents.ProviderReady()
                    } else {
                        OpenFeatureProviderEvents.ProviderStale()
                    }
                )
            }
        }
        testScheduler.advanceUntilIdle()

        // Assert the job actually finished and didn't hang
        assertTrue(job.isCompleted)
        slowSubscriber.cancel()

        // Assert the state correctly processed them
        val finalStatus = state.getStatus()
        assertTrue(finalStatus is OpenFeatureStatus.Ready || finalStatus is OpenFeatureStatus.Stale)
    }

    @Test
    fun `ProviderRepository clearAll should not deadlock against busy domain state shutdowns`() = runTest {
        val repository = ProviderRepository()
        val state = repository.getOrCreateState("deadlock-domain")

        // Thread A: Holds providerMutex and tries to get repositoryMutex
        val threadA = launch(StandardTestDispatcher(testScheduler)) {
            state.providerMutex.withLock {
                delay(50L) // Wait to ensure Thread B traps repositoryMutex
                repository.getOrCreateState("new-domain") // Wants repositoryMutex
            }
        }

        // Thread B: Runs clearAll
        val threadB = launch(StandardTestDispatcher(testScheduler)) {
            repository.clearAll() // Holds repositoryMutex initially, then wants providerMutex (via shutdown)
        }

        testScheduler.advanceUntilIdle()

        // If the vulnerability exists, both threads will be permanently blocked!
        assertTrue(threadA.isCompleted)
        assertTrue(threadB.isCompleted)
    }

    @Test
    fun testConcurrentGlobalAndDomainContextUpdatesAreSynchronized() = runTest {
        val testDomain = "syncTestDomain"
        val state = ProviderRepository().getOrCreateState(testDomain)
        val globalContextMutex = kotlinx.coroutines.sync.Mutex()

        val jobA = launch(StandardTestDispatcher(testScheduler)) {
            state.contextMutex.withLock {
                state.context = ImmutableContext(attributes = mapOf("A" to Value.Boolean(true)))
                val globalCtx = globalContextMutex.withLock { null }
                state.mergedContext = globalCtx?.mergeWith(state.context!!) ?: state.context
            }
        }

        val jobB = launch(StandardTestDispatcher(testScheduler)) {
            globalContextMutex.withLock {
                state.contextMutex.withLock {
                    val globalCtx = ImmutableContext(attributes = mapOf("B" to Value.Boolean(true)))
                    state.mergedContext = state.context?.let { globalCtx.mergeWith(it) } ?: globalCtx
                }
            }
        }

        testScheduler.advanceUntilIdle()

        assertTrue(jobA.isCompleted)
        assertTrue(jobB.isCompleted)
        assertNotNull(state.mergedContext)
    }

    @Test
    fun testContextHooksBypassUninitializedProviders() = runTest {
        val testDomain = "bypassDomain"
        OpenFeatureAPI.getClient(testDomain).setProviderAndWait(
            NoOpProvider(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        var initFired = false
        var hookFired = false

        val slowProvider = object : NoOpProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                delay(100) // Suspend strictly
                initFired = true
            }

            override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
                hookFired = true
            }
        }

        // Simultaneously trigger setProvider and Context Updates
        val contextJob = launch {
            // Trigger Context Update first natively queuing onto IO
            OpenFeatureAPI.setEvaluationContext(
                testDomain,
                ImmutableContext(attributes = mapOf("key" to Value.Boolean(true))),
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }

        val providerJob = launch {
            delay(10) // Queue Provider Swap Second Native Block!
            OpenFeatureAPI.getClient(testDomain).setProviderAndWait(
                slowProvider,
                dispatcher = StandardTestDispatcher(testScheduler)
            )
        }

        testScheduler.advanceUntilIdle()

        assertTrue(providerJob.isCompleted)
        assertTrue(contextJob.isCompleted)
        assertTrue(initFired, "Provider must have initialized correctly successfully")
        assertFalse(hookFired, "Context Hook MUST have bypassed natively because Provider was strictly uninitialized!")
    }

    @Test
    fun testShutdownCancelsPhantomContextCoroutines() = runTest {
        var hookFired = false

        val testDomain = "phantomDomain"
        OpenFeatureAPI.getClient(testDomain).setProviderAndWait(
            NoOpProvider(),
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        val zombieProvider = object : NoOpProvider() {
            override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
                delay(50)
                hookFired = true
            }
        }

        OpenFeatureAPI.getClient(testDomain).setProviderAndWait(
            zombieProvider,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        // Trigger a Global Evaluation Context which runs async natively
        val evalJob = launch(StandardTestDispatcher(testScheduler)) {
            OpenFeatureAPI.setEvaluationContext(ImmutableContext())
        }

        delay(10) // Yield to allow Evaluation Cascade instantiation cleanly

        // Execute Shutdown strictly mid-way targeting precise cancellation interception!
        OpenFeatureAPI.shutdown()

        testScheduler.advanceUntilIdle()

        assertFalse(
            hookFired,
            "Shutdown MUST explicitly track and natively intercept Phantom Domain Hook evaluations statically!"
        )
    }

    @Test
    fun testConcurrentSetEvaluationContextIsThreadSafe() = runTest {
        val testDomain = "concurrent-domain"
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Spawn 100 concurrent requests into the test dispatcher queue to induce rapid cancellation overrides natively
        for (index in 0 until 100) {
            OpenFeatureAPI.setEvaluationContext(
                testDomain,
                ImmutableContext(attributes = mapOf("key" to Value.Integer(index))),
                dispatcher = testDispatcher
            )
        }

        // Let the test environment gracefully resolve all internally queued lock transfers and cancellations
        testScheduler.advanceUntilIdle()

        // If the JobMutex works, no CancellationExceptions should have leaked natively and
        // the final surviving context should strictly be mathematically intact and valid!
        val resultingContext = OpenFeatureAPI.getEvaluationContext(testDomain)
        assertNotNull(resultingContext, "Context MUST NOT be completely destroyed by concurrent Job cancellations!")
        assertTrue(resultingContext.asMap().containsKey("key"))

        OpenFeatureAPI.shutdown()
    }
}