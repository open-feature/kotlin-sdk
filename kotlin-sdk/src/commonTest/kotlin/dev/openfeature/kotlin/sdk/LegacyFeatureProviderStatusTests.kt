package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for **legacy** providers: [FeatureProvider] without [StateManagingProvider].
 * Status is driven by the SDK-managed buffer and mirroring of [FeatureProvider.observe].
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
        val first = SpyProvider()
        val second = SpyProvider()
        OpenFeatureAPI.setProviderAndWait(first)
        OpenFeatureAPI.setProviderAndWait(second)
        waitAssert { assertEquals(1, first.shutdownCalls.value) }
        assertEquals(0, second.shutdownCalls.value)
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
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