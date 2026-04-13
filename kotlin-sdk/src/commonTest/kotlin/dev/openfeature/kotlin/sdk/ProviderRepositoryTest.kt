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
import kotlin.test.assertNotEquals
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
        state.shutdown()
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
}