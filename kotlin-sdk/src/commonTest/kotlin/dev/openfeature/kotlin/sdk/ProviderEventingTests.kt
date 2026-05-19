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
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()
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
        val providerErrors = mutableListOf<OpenFeatureProviderEvents.ProviderError>()
        val statusJob = async(testDispatcher) {
            OpenFeatureAPI.statusFlow.toCollection(statusList)
        }
        val errorsJob = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderError>().collect {
                providerErrors.add(it)
            }
        }

        OpenFeatureAPI.setProviderAndWait(
            provider,
            initialContext = ImmutableContext()
        )
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()
        waitAssert {
            assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        }
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("new"))
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()
        statusJob.cancelAndJoin()
        errorsJob.cancelAndJoin()
        waitAssert {
            assertTrue(providerErrors.isNotEmpty())
        }
        assertEquals(OpenFeatureStatus.Ready, statusList.first())
        assertEquals(OpenFeatureStatus.NotReady, statusList.last())
        assertTrue(statusList.any { it == OpenFeatureStatus.Reconciling })
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
        flushDispatchersDefault()
        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("first.v2"))
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()
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
        flushDispatchersDefault()
        // emits ProviderStale + ProviderStale + ProviderStale
        OpenFeatureAPI.getClient().track("hello-world")
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()

        // emits ProviderStale + ProviderConfigurationChanged
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("second.v2"))
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()

        OpenFeatureAPI.shutdown()
        flushDispatchersDefault()
        job.cancelAndJoin()
        val expected = listOf(
            OpenFeatureProviderEvents.ProviderReady(),
            OpenFeatureProviderEvents.ProviderStale(),
            OpenFeatureProviderEvents.ProviderConfigurationChanged(),
            OpenFeatureProviderEvents.ProviderReady(),
            OpenFeatureProviderEvents.ProviderStale(),
            OpenFeatureProviderEvents.ProviderStale(),
            OpenFeatureProviderEvents.ProviderStale(),
            OpenFeatureProviderEvents.ProviderStale(),
            OpenFeatureProviderEvents.ProviderConfigurationChanged()
        )
        waitAssert {
            assertEquals(expected, emittedEvents)
        }
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
        flushDispatchersDefault()
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
        flushDispatchersDefault()
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("ctx.v2"))
        testScheduler.advanceUntilIdle()
        flushDispatchersDefault()
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