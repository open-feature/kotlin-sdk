package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.helpers.LegacyMinimalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for **legacy** providers: [FeatureProvider] without [StateManagingProvider].
 * Status is derived from [FeatureProvider.observe] via the SDK adapter (same as other OpenFeature SDKs).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyFeatureProviderStatusTests {

    @BeforeTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun legacy_provider_init_leaves_sdk_status_ready_after_setProviderAndWait() = runTest {
        val provider = LegacyControllableProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
    }

    @Test
    fun legacy_observe_emissions_are_mirrored_into_sdk_status() = runTest {
        val provider = LegacyControllableProvider()
        OpenFeatureAPI.setProviderAndWait(provider)

        launch {
            provider.events.emit(OpenFeatureProviderEvents.ProviderStale())
        }.join()

        waitAssert { assertEquals(OpenFeatureStatus.Stale, OpenFeatureAPI.getStatus()) }
    }

    @Test
    fun legacy_context_change_invokes_onContextSet_and_emits_on_status_flow() = runTest {
        val provider = LegacySlowContextProvider()
        val statuses = mutableListOf<OpenFeatureStatus>()
        val collector = launch {
            OpenFeatureAPI.statusFlow.collect { statuses.add(it) }
        }

        // Pin a known initial context so this test is not affected by leftover global context from other tests.
        OpenFeatureAPI.setProviderAndWait(provider, initialContext = ImmutableContext("legacy-init"))
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("ctx-a"))
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        assertEquals(1, provider.onContextSetCalls, "SDK should invoke onContextSet for legacy providers")

        collector.cancelAndJoin()

        assertTrue(statuses.isNotEmpty(), "statusFlow should emit for legacy provider lifecycle, got $statuses")
    }

    @Test
    fun legacy_getStatus_reads_sdk_buffer_not_provider_state_flow() = runTest {
        val provider = LegacyControllableProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        assertTrue(OpenFeatureAPI.getProvider() !is StateManagingProvider)
    }

    @Test
    fun legacy_swap_to_second_legacy_provider_shuts_down_first() = runTest {
        val first = LegacyMinimalProvider()
        val second = LegacyMinimalProvider()
        OpenFeatureAPI.setProviderAndWait(first)
        OpenFeatureAPI.setProviderAndWait(second)
        waitAssert { assertEquals(1, first.shutdownCalls.value) }
        assertEquals(0, second.shutdownCalls.value)
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    /**
     * [LegacyFeatureProviderAdapter] calls [FeatureProvider.inner] (same [observe] [Flow] the adapter
     * collects) from [StateManagingProvider.shutdown] **before** cancelling the collect job, so a final
     * [OpenFeatureProviderEvents] from [FeatureProvider.shutdown] is still applied to the adapter
     * [StateFlow] status. This uses a parallel test collector on the same inner [observe] stream the
     * adapter uses, plus [shutdownLastEventAccepted] on the provider, to assert shutdown-time emission
     * without import-time access to the internal adapter.
     */
    @Test
    fun legacy_shutdown_tryEmit_last_event_visible_on_shared_observe_before_swap_completes() = runTest {
        val first = LegacyEmitsStaleOnShutdown()
        val seen = mutableListOf<OpenFeatureProviderEvents>()
        // Unconfined: subscribe the test collector to [first.observe] immediately, so
        // MutableSharedFlow.tryEmit(ProviderStale) in shutdown is not dropped as "no pending subscribers".
        val collectJob = launch(Dispatchers.Unconfined) {
            first.observe().collect { seen.add(it) }
        }
        yield()
        OpenFeatureAPI.setProviderAndWait(first)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }

        OpenFeatureAPI.setProviderAndWait(LegacyMinimalProvider())
        // Allow the inner SharedFlow to dispatch shutdown-time Stale to the parallel test collector
        // (and to the adapter's collect) even if that happens one scheduling round later in runTest.
        waitAssert {
            assertTrue(
                seen.any { it is OpenFeatureProviderEvents.ProviderStale },
                "inner.observe() should deliver ProviderStale from shutdown() " +
                    "while adapter collect is still active; got $seen"
            )
        }
        collectJob.cancelAndJoin()

        assertTrue(
            first.shutdownLastEventAccepted,
            "tryEmit(ProviderStale) in inner.shutdown() should succeed " +
                "(non-empty subscribers + buffer for shutdown-time emit)"
        )
    }

    @Test
    fun legacy_provider_error_event_is_mirrored_to_sdk_error_status() = runTest {
        val provider = LegacyControllableProvider()
        OpenFeatureAPI.setProviderAndWait(provider)

        launch {
            provider.events.emit(
                OpenFeatureProviderEvents.ProviderError(
                    eventDetails = OpenFeatureProviderEvents.EventDetails(
                        message = "legacy test error",
                        errorCode = ErrorCode.GENERAL
                    )
                )
            )
        }.join()

        waitAssert {
            assertTrue(OpenFeatureAPI.getStatus() is OpenFeatureStatus.Error)
        }
    }
}

/** Legacy-only provider with a controllable [observe] stream for mirror tests. */
private class LegacyControllableProvider : FeatureProvider {
    override val hooks: List<Hook<*>> = listOf()
    override val metadata: ProviderMetadata = LegacyTestMetadata()

    val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        // no-op
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // no-op
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> = ProviderEvaluation(defaultValue)

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = ProviderEvaluation(defaultValue)

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = ProviderEvaluation(defaultValue)

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = ProviderEvaluation(defaultValue)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = ProviderEvaluation(defaultValue)
}

private class LegacyTestMetadata(override val name: String? = "legacy-test") : ProviderMetadata

/**
 * [shutdown] [tryEmit]s [OpenFeatureProviderEvents.ProviderStale] for tests that the legacy adapter still
 * collects the same [observe] stream through [StateManagingProvider.shutdown] before the collect [Job] is cancelled.
 */
private class LegacyEmitsStaleOnShutdown : FeatureProvider {
    override val hooks: List<Hook<*>> = listOf()
    override val metadata: ProviderMetadata = LegacyTestMetadata("legacy-stale-on-shutdown")
    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 16)

    /** True once [OpenFeatureProviderEvents.ProviderStale] was accepted into [events] in [shutdown]. */
    var shutdownLastEventAccepted: Boolean = false
        private set

    override suspend fun initialize(initialContext: EvaluationContext?) {
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        shutdownLastEventAccepted = events.tryEmit(OpenFeatureProviderEvents.ProviderStale())
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {}

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> = ProviderEvaluation(defaultValue)

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = ProviderEvaluation(defaultValue)

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = ProviderEvaluation(defaultValue)

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = ProviderEvaluation(defaultValue)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = ProviderEvaluation(defaultValue)
}

/** Legacy provider with a short suspend in [onContextSet] so [OpenFeatureStatus.Reconciling] is observable. */
private class LegacySlowContextProvider : FeatureProvider {
    override val hooks: List<Hook<*>> = listOf()
    override val metadata: ProviderMetadata = LegacyTestMetadata("legacy-slow-context")

    var onContextSetCalls: Int = 0
        private set

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        // no-op
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        onContextSetCalls++
        delay(20)
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> = ProviderEvaluation(defaultValue)

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> = ProviderEvaluation(defaultValue)

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> = ProviderEvaluation(defaultValue)

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> = ProviderEvaluation(defaultValue)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> = ProviderEvaluation(defaultValue)
}