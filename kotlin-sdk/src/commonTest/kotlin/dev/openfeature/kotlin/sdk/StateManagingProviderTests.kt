package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A provider implementing [StateManagingProvider] reports its own lifecycle, and the SDK adds nothing of
 * its own; a provider that does not gets those events synthesised on its behalf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagingProviderTests {

    @BeforeTest
    fun setUp() = runTest {
        OpenFeatureAPI.shutdown()
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    /** Reports whatever it is told to report, from inside `initialize`, as the specification requires. */
    private class ReportingProvider(
        private val onInitialize: List<OpenFeatureProviderEvents>,
        override val metadata: ProviderMetadata = object : ProviderMetadata {
            override val name: String = "reporting"
        }
    ) : StateManagingProvider {
        override val hooks: List<Hook<*>> = listOf()
        private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 16)
        var initializeCalls = 0
        var shutdownCalls = 0

        override suspend fun initialize(initialContext: EvaluationContext?) {
            initializeCalls++
            onInitialize.forEach { events.emit(it) }
        }

        override fun shutdown() {
            shutdownCalls++
        }

        override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
            events.emit(OpenFeatureProviderEvents.ProviderReconciling())
            events.emit(OpenFeatureProviderEvents.ProviderContextChanged())
        }

        override fun observe(): Flow<OpenFeatureProviderEvents> = events

        override fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?) =
            ProviderEvaluation(!defaultValue)

        override fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?) =
            ProviderEvaluation(defaultValue)

        override fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?) =
            ProviderEvaluation(defaultValue)

        override fun getLongEvaluation(key: String, defaultValue: Long, context: EvaluationContext?) =
            ProviderEvaluation(defaultValue)

        override fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?) =
            ProviderEvaluation(defaultValue)

        override fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?) =
            ProviderEvaluation(defaultValue)
    }

    /** Emits its own readiness, so the SDK has no need to stand in for it. */
    private class SelfReportingLegacyProvider : DoSomethingProvider(
        metadata = object : ProviderMetadata {
            override val name: String = "self-reporting-legacy"
        }
    ) {
        override suspend fun initialize(initialContext: EvaluationContext?) {
            events.emit(OpenFeatureProviderEvents.ProviderReady())
        }
    }

    @Test
    fun statusIsWhateverTheProviderReported() = runTest {
        val provider = ReportingProvider(listOf(OpenFeatureProviderEvents.ProviderReady()))
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun readinessReportedDuringInitializeIsNotOverwrittenBySomethingLater() = runTest {
        // The SDK used to publish Ready after initialize returned, discarding this.
        val provider = ReportingProvider(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale()
            )
        )
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))
        testScheduler.advanceUntilIdle()

        assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus())
    }

    @Test
    fun reportedFatalErrorBecomesFatalStatus() = runTest {
        val provider = ReportingProvider(
            listOf(
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(
                        message = "unrecoverable",
                        errorCode = ErrorCode.PROVIDER_FATAL
                    )
                )
            )
        )
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))

        assertIs<OpenFeatureStatus.Fatal>(OpenFeatureAPI.getStatus())
    }

    @Test
    fun reconciliationReportedByTheProviderIsNotDuplicatedByTheSdk() = runTest {
        val provider = ReportingProvider(listOf(OpenFeatureProviderEvents.ProviderReady()))
        val dispatcher = StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = dispatcher)

        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) } }
        testScheduler.runCurrent()
        received.clear()

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("ctx"))
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            received.map { it::class }
        )
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun registrationWaitsForAProviderThatReportsNothing() = runTest {
        // The SDK does not invent a status, so a silent provider leaves registration unsettled.
        val silent = ReportingProvider(emptyList())

        val settled = withTimeoutOrNull(1_000) {
            OpenFeatureAPI.setProviderAndWait(silent, dispatcher = StandardTestDispatcher(testScheduler))
            true
        }

        assertNull(settled)
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
    }

    @Test
    fun aLegacyProviderThatReportsForItselfIsNotReportedForTwice() = runTest {
        val provider = SelfReportingLegacyProvider()
        val dispatcher = StandardTestDispatcher(testScheduler)

        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) } }
        testScheduler.runCurrent()

        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = dispatcher)
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(listOf(OpenFeatureProviderEvents.ProviderReady::class), received.map { it::class })
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun aSilentLegacyProviderIsReportedReadyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch { OpenFeatureAPI.observe<OpenFeatureProviderEvents>().collect { received.add(it) } }
        testScheduler.runCurrent()

        OpenFeatureAPI.setProviderAndWait(NoOpProvider(), dispatcher = dispatcher)
        testScheduler.advanceUntilIdle()
        job.cancelAndJoin()

        assertEquals(listOf(OpenFeatureProviderEvents.ProviderReady::class), received.map { it::class })
    }

    @Test
    fun getProviderReturnsTheRegisteredInstanceNotAWrapper() = runTest {
        val provider = NoOpProvider()
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))

        assertSame(provider, OpenFeatureAPI.getProvider())
        assertTrue(OpenFeatureAPI.getProvider() !is StateManagingProvider)
    }

    @Test
    fun aProviderThatFailedToInitializeCanBeRegisteredAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var failNext = true
        val provider = object : DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "retryable"
            }
        ) {
            var initializeCalls = 0

            override suspend fun initialize(initialContext: EvaluationContext?) {
                initializeCalls++
                if (failNext) throw OpenFeatureError.GeneralError("first attempt fails")
            }
        }

        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = dispatcher)
        testScheduler.advanceUntilIdle()
        assertIs<OpenFeatureStatus.Error>(OpenFeatureAPI.getStatus())

        failNext = false
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = dispatcher)
        testScheduler.advanceUntilIdle()

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        assertEquals(2, provider.initializeCalls)
    }

    @Test
    fun shutdownIsRepeatable() = runTest {
        val provider = ReportingProvider(listOf(OpenFeatureProviderEvents.ProviderReady()))
        OpenFeatureAPI.setProviderAndWait(provider, dispatcher = StandardTestDispatcher(testScheduler))

        OpenFeatureAPI.shutdown()
        OpenFeatureAPI.shutdown()

        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        assertEquals(1, provider.shutdownCalls)
    }
}