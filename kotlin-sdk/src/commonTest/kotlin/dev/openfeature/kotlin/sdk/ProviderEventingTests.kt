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
                // no-op
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
            assertEquals(6, statusList.size)
        }
        assertEquals(OpenFeatureStatus.NotReady, statusList[0])
        assertEquals(OpenFeatureStatus.Ready, statusList[1])
        assertEquals(OpenFeatureStatus.Reconciling, statusList[2])
        assertTrue(statusList[3] is OpenFeatureStatus.Error)
        assertEquals(OpenFeatureStatus.Ready, statusList[4])
        assertEquals(OpenFeatureStatus.NotReady, statusList[5])
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
        // Let the collector subscribe: the SDK relays events live and does not replay past ones.
        testScheduler.runCurrent()

        // emits ProviderReady
        OpenFeatureAPI.setProviderAndWait(
            firstProvider,
            initialContext = ImmutableContext("first"),
            dispatcher = testDispatcher
        )
        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("first.v2"))
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderConfigurationChanged::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            emittedEvents.map { it::class }
        )
        assertTrue(emittedEvents.all { it.eventDetails?.providerName == "First Provider" })
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
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("second.v2"))
        testScheduler.advanceUntilIdle()

        OpenFeatureAPI.shutdown()
        job.cancelAndJoin()
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderConfigurationChanged::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class,
                OpenFeatureProviderEvents.ProviderReady::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderStale::class,
                OpenFeatureProviderEvents.ProviderConfigurationChanged::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            emittedEvents.map { it::class }
        )
        // The relay attributes each event to whichever provider was active when it was emitted.
        assertEquals(
            List(5) { "First Provider" } + List(8) { "Second Provider" },
            emittedEvents.map { it.eventDetails?.providerName }
        )
    }

    @Test
    fun clientObserveMatchesApiObserveWhenCollectingAllProviderEvents() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val provider = OverlyEmittingProvider("Client parity provider")
        val fromApi = mutableListOf<OpenFeatureProviderEvents>()
        val fromClient = mutableListOf<OpenFeatureProviderEvents>()
        val client = OpenFeatureAPI.getClient("test")

        val apiJob = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { fromApi.add(it) }
        }
        val clientJob = launch {
            client.observe().collect { fromClient.add(it) }
        }
        // Let both collectors subscribe: the SDK relays events live and does not replay past ones.
        testScheduler.runCurrent()

        OpenFeatureAPI.setProviderAndWait(
            provider,
            initialContext = ImmutableContext("ctx"),
            dispatcher = testDispatcher
        )
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        apiJob.cancelAndJoin()
        clientJob.cancelAndJoin()

        assertTrue(fromApi.isNotEmpty())
        assertEquals(fromApi, fromClient)
    }

    @Test
    fun clientObserveFiltersByReifiedEventType() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
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
        // Let both collectors subscribe: the SDK relays events live and does not replay past ones.
        testScheduler.runCurrent()

        OpenFeatureAPI.setProviderAndWait(
            provider,
            initialContext = ImmutableContext("ctx"),
            dispatcher = testDispatcher
        )
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("ctx.v2"))
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        staleJob.cancelAndJoin()
        configJob.cancelAndJoin()

        assertEquals(1, staleEvents.size)
        assertEquals(1, configurationChangedEvents.size)
        assertEquals("filter-by-type", staleEvents.single().eventDetails?.providerName)
        assertEquals("filter-by-type", configurationChangedEvents.single().eventDetails?.providerName)
    }
}