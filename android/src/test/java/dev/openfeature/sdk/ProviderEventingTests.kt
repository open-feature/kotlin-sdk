package dev.openfeature.sdk

import dev.openfeature.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.sdk.exceptions.OpenFeatureError
import dev.openfeature.sdk.helpers.DoSomethingProvider
import dev.openfeature.sdk.helpers.OverlyEmittingProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class ProviderEventingTests {

    @Before
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
                        OpenFeatureError.ProviderNotReadyError(
                            "test error"
                        )
                    )
                )
                delay(healDelayMillis)
                flow.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged)
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
            Assert.assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        }
        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("new"))
        testScheduler.advanceUntilIdle()
        OpenFeatureAPI.shutdown()
        testScheduler.advanceUntilIdle()
        j.cancelAndJoin()
        waitAssert {
            Assert.assertEquals(5, statusList.size)
        }
        Assert.assertEquals(OpenFeatureStatus.Ready, statusList[0])
        Assert.assertEquals(OpenFeatureStatus.Reconciling, statusList[1])
        Assert.assertTrue(statusList[2] is OpenFeatureStatus.Error)
        Assert.assertEquals(OpenFeatureStatus.Ready, statusList[3])
        Assert.assertEquals(OpenFeatureStatus.NotReady, statusList[4])
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
        Assert.assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady,
                OpenFeatureProviderEvents.ProviderStale,
                OpenFeatureProviderEvents.ProviderConfigurationChanged
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
        Assert.assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady,
                OpenFeatureProviderEvents.ProviderStale,
                OpenFeatureProviderEvents.ProviderConfigurationChanged,
                OpenFeatureProviderEvents.ProviderReady,
                OpenFeatureProviderEvents.ProviderStale,
                OpenFeatureProviderEvents.ProviderStale,
                OpenFeatureProviderEvents.ProviderStale,
                OpenFeatureProviderEvents.ProviderStale,
                OpenFeatureProviderEvents.ProviderConfigurationChanged
            ),
            emittedEvents
        )
    }
}