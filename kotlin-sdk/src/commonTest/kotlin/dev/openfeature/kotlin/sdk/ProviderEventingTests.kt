package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.OverlyEmittingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
            override suspend fun initialize(initialContext: EvaluationContext?) {
                statusTracker.send(OpenFeatureProviderEvents.ProviderReady())
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
                statusTracker.send(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(
                            message = "test error",
                            errorCode = ErrorCode.PROVIDER_NOT_READY
                        )
                    )
                )
                delay(healDelayMillis)
                statusTracker.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
            }
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
            assertEquals(3, statusList.size, "collected $statusList")
        }
        // The provider reports readiness, then an error, then a configuration change. A
        // configuration change carries no status, so it no longer clears the error: the SDK reports
        // what the provider reported rather than concluding the reconciliation succeeded.
        assertEquals(OpenFeatureStatus.Ready, statusList[0])
        assertTrue(statusList[1] is OpenFeatureStatus.Error)
        assertEquals(OpenFeatureStatus.NotReady, statusList[2])
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

        OpenFeatureAPI.setProviderAndWait(provider, initialContext = ImmutableContext("ctx"))
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        apiJob.cancelAndJoin()
        clientJob.cancelAndJoin()

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