package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.OverlyEmittingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEventingTests {

    private val noopShutdownEvent = OpenFeatureProviderEvents.ProviderError(
        OpenFeatureProviderEvents.EventDetails(
            message = "No-op provider shut down; not ready for evaluation",
            errorCode = ErrorCode.PROVIDER_NOT_READY
        )
    )

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
                flow.emit(OpenFeatureProviderEvents.ProviderReady())
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
                flow.emit(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(
                            message = "test error",
                            errorCode = ErrorCode.PROVIDER_NOT_READY
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
        assertEquals(OpenFeatureStatus.NotReady, statusList[2])
        assertEquals(OpenFeatureStatus.Ready, statusList[3])
        assertEquals(OpenFeatureStatus.NotReady, statusList[4])
    }

    @Test
    fun testProviderEventFlowShouldSupportSwappingProviders() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val firstProvider = OverlyEmittingProvider("First Provider")
        val secondProvider = OverlyEmittingProvider("Second Provider")

        val emittedEvents = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect {
                emittedEvents.add(it)
            }
        }
        advanceUntilIdle()

        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            firstProvider,
            initialContext = ImmutableContext("first"),
            dispatcher = testDispatcher
        )
        advanceTimeBy(2000)
        advanceUntilIdle()
        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("first.v2"))
        advanceUntilIdle()
        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            secondProvider,
            initialContext = ImmutableContext("second"),
            dispatcher = testDispatcher
        )
        advanceTimeBy(2000)
        advanceUntilIdle()
        // emits ProviderStale + ProviderStale + ProviderStale
        OpenFeatureAPI.getClient().track("hello-world")
        advanceUntilIdle()

        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("second.v2"))
        advanceUntilIdle()

        OpenFeatureAPI.shutdown()
        advanceUntilIdle()
        job.cancelAndJoin()
        assertEquals(
            listOf(
                noopShutdownEvent,
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                noopShutdownEvent
            ),
            emittedEvents
        )
    }

    @Test
    fun clientObserveMatchesApiObserveWhenCollectingAllProviderEvents() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        suspend fun collectEvents(useClientObserve: Boolean): List<OpenFeatureProviderEvents> {
            val events = mutableListOf<OpenFeatureProviderEvents>()
            val client = OpenFeatureAPI.getClient("test")
            val provider = OverlyEmittingProvider("Client parity provider")
            val job = launch {
                if (useClientObserve) {
                    client.observe().collect { events.add(it) }
                } else {
                    OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { events.add(it) }
                }
            }
            yield()
            OpenFeatureAPI.setProviderAndWait(
                provider,
                initialContext = ImmutableContext("ctx"),
                dispatcher = testDispatcher
            )
            testScheduler.advanceUntilIdle()
            OpenFeatureAPI.shutdown()
            job.cancelAndJoin()
            return events.toList()
        }

        val fromApi = collectEvents(useClientObserve = false)
        val fromClient = collectEvents(useClientObserve = true)

        assertTrue(fromApi.isNotEmpty())
        assertEquals(fromApi, fromClient)
    }

    @Test
    fun clientObserveFiltersByReifiedEventType() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = OverlyEmittingProvider("filter-by-type")
        val allEvents = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { allEvents.add(it) }
        }
        advanceUntilIdle()

        val waitInit = launch {
            OpenFeatureAPI.setProviderAndWait(
                provider,
                initialContext = ImmutableContext("ctx"),
                dispatcher = testDispatcher
            )
        }
        advanceTimeBy(2000)
        advanceUntilIdle()
        waitInit.join()
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("ctx.v2"))
        advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        advanceUntilIdle()
        job.cancelAndJoin()

        val staleEvents = allEvents.filterIsInstance<OpenFeatureProviderEvents.ProviderStale>()
        val configurationChangedEvents =
            allEvents.filterIsInstance<OpenFeatureProviderEvents.ProviderConfigurationChanged>()
        assertEquals(listOf(OpenFeatureProviderEvents.ProviderStale()), staleEvents)
        assertEquals(
            listOf(OpenFeatureProviderEvents.ProviderConfigurationChanged()),
            configurationChangedEvents
        )
    }
}