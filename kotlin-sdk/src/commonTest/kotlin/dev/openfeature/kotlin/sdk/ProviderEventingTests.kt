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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            firstProvider,
            initialContext = ImmutableContext("first")
        )
        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("first.v2"))
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged()
            ),
            emittedEvents
        )
        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            secondProvider,
            initialContext = ImmutableContext("second")
        )
        testScheduler.advanceUntilIdle()
        // emits ProviderStale + ProviderStale + ProviderStale
        OpenFeatureAPI.getClient().track("hello-world")
        testScheduler.advanceUntilIdle()

        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("second.v2"))
        testScheduler.advanceUntilIdle()

        OpenFeatureAPI.shutdown()
        job.cancelAndJoin()
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderStale(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged()
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
        val provider = OverlyEmittingProvider("filter-by-type")
        val client = OpenFeatureAPI.getClient("filter-by-type")
        val staleEvents = mutableListOf<OpenFeatureProviderEvents.ProviderStale>()
        val configurationChangedEvents =
            mutableListOf<OpenFeatureProviderEvents.ProviderConfigurationChanged>()

        val staleJob = launch {
            client.observe()
                .filterIsInstance<OpenFeatureProviderEvents.ProviderStale>()
                .collect { staleEvents.add(it) }
        }
        val configJob = launch {
            client.observe()
                .filterIsInstance<OpenFeatureProviderEvents.ProviderConfigurationChanged>()
                .collect { configurationChangedEvents.add(it) }
        }

        OpenFeatureAPI.setProviderAndWait(provider, initialContext = ImmutableContext("ctx"))
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("ctx.v2"))
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        staleJob.cancelAndJoin()
        configJob.cancelAndJoin()

        assertEquals(listOf(OpenFeatureProviderEvents.ProviderStale()), staleEvents)
        assertEquals(
            listOf(OpenFeatureProviderEvents.ProviderConfigurationChanged()),
            configurationChangedEvents
        )
    }
}