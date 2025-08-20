package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiProviderTests {

    @Test
    fun deduplicates_providers_by_name() {
        val p1 = FakeEventProvider(name = "dup")
        val p2 = FakeEventProvider(name = "dup")
        val p3 = FakeEventProvider(name = "unique")

        val multi = MultiProvider(listOf(p1, p2, p3))

        assertEquals(2, multi.getProviderCount())
    }

    @Test
    fun forwards_lifecycle_calls_to_underlying_providers() = runTest {
        val provider = FakeEventProvider(name = "p")
        val multi = MultiProvider(listOf(provider))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()
        assertEquals(1, provider.initializeCalls)

        val ctx = ImmutableContext("user-123")
        multi.onContextSet(null, ctx)
        assertEquals(1, provider.onContextSetCalls)

        multi.shutdown()
        assertEquals(1, provider.shutdownCalls)
        initJob.cancelAndJoin()
    }

    @Test
    fun observes_events_and_applies_precedence_after_configuration_change() = runTest {
        // Including ProviderConfigurationChanged first allows subsequent lower-precedence READY to emit
        val provider = FakeEventProvider(
            name = "p",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged,
                OpenFeatureProviderEvents.ProviderReady,
                OpenFeatureProviderEvents.ProviderStale
            )
        )
        val multi = MultiProvider(listOf(provider))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        // The last emitted event should be STALE given the sequence above
        val last = multi.observe().first()
        assertEquals(OpenFeatureProviderEvents.ProviderStale, last)
        initJob.cancelAndJoin()
    }

    @Test
    fun uses_strategy_for_evaluations_and_preserves_unique_order() {
        val p1 = FakeEventProvider(name = "A")
        val dup = FakeEventProvider(name = "A")
        val p2 = FakeEventProvider(name = "B")

        val recorder = RecordingStrategy(returnValue = ProviderEvaluation(true))
        val multi = MultiProvider(listOf(p1, dup, p2), strategy = recorder)

        val eval = multi.getBooleanEvaluation("flag", false, null)

        assertEquals(true, eval.value)
        assertEquals(listOf("A", "B"), recorder.lastProviderNames)
    }

    @Test
    fun aggregates_event_precedence_across_multiple_providers() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged,
                OpenFeatureProviderEvents.ProviderReady
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged,
                OpenFeatureProviderEvents.ProviderStale
            )
        )
        val c = FakeEventProvider(
            name = "C",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged,
                OpenFeatureProviderEvents.ProviderNotReady,
                OpenFeatureProviderEvents.ProviderError(
                    dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.GeneralError("boom")
                )
            )
        )
        val multi = MultiProvider(listOf(a, b, c))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        val last = multi.observe().first()
        assertIs<OpenFeatureProviderEvents.ProviderError>(last)
        initJob.cancelAndJoin()
    }

    @Test
    fun metadata_includes_original_metadata_and_handles_unnamed_providers() {
        val named = FakeEventProvider(name = "A")
        val unnamed = FakeEventProvider(name = null)

        val multi = MultiProvider(listOf(named, unnamed))

        val metadataString = multi.metadata.name ?: error("metadata.name should not be null")

        // Simple substring assertions to validate structure without full JSON parsing
        assertTrue(metadataString.contains("\"name\":\"multiprovider\""))
        assertTrue(metadataString.contains("\"originalMetadata\""))
        // Contains the named provider key
        assertTrue(metadataString.contains("\"A\""))
        // Contains at least one generated key for unnamed providers
        assertTrue(metadataString.contains("\"<unnamed>-"))
    }

    @Test
    fun shutdown_aggregates_errors_and_reports_provider_names() {
        val ok = FakeEventProvider(name = "ok")
        val bad1 = FakeEventProvider(name = "bad1", shutdownThrowable = IllegalStateException("oops1"))
        val bad2 = FakeEventProvider(name = null, shutdownThrowable = RuntimeException("oops2"))

        val multi = MultiProvider(listOf(ok, bad1, bad2))

        val error = assertFailsWith<OpenFeatureError.GeneralError> {
            multi.shutdown()
        }

        // Message contains each provider and message on separate lines
        val msg = error.message
        assertTrue(msg.contains("bad1: oops1"))
        // unnamed should be rendered as "<unnamed>"
        assertTrue(msg.contains("<unnamed>: oops2"))

        // Suppressed should include one per failure
        assertEquals(2, error.suppressedExceptions.size)
        val suppressedMessages = error.suppressedExceptions.map { it.message ?: "" }
        assertTrue(suppressedMessages.any { it.contains("Provider 'bad1' shutdown failed: oops1") })
        assertTrue(suppressedMessages.any { it.contains("Provider '<unnamed>' shutdown failed: oops2") })
    }
}

// Helpers

private class FakeEventProvider(
    private val name: String?,
    private val eventsToEmitOnInit: List<OpenFeatureProviderEvents> = emptyList(),
    private val shutdownThrowable: Throwable? = null
) : FeatureProvider {
    override val hooks: List<Hook<*>> = emptyList()
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = this@FakeEventProvider.name
    }

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 16)

    var initializeCalls: Int = 0
        private set
    var shutdownCalls: Int = 0
        private set
    var onContextSetCalls: Int = 0
        private set

    override suspend fun initialize(initialContext: EvaluationContext?) {
        initializeCalls += 1
        // Emit any preconfigured events during initialize so MultiProvider observers receive them
        eventsToEmitOnInit.forEach { events.emit(it) }
    }

    override fun shutdown() {
        shutdownCalls += 1
        shutdownThrowable?.let { throw it }
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        onContextSetCalls += 1
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        return ProviderEvaluation(defaultValue)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        return ProviderEvaluation(defaultValue)
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        return ProviderEvaluation(defaultValue)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        return ProviderEvaluation(defaultValue)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        return ProviderEvaluation(defaultValue)
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events
}

private class RecordingStrategy(
    private val returnValue: ProviderEvaluation<Boolean>
) : Strategy {
    var lastProviderNames: List<String> = emptyList()
        private set

    override fun <T> evaluate(
        providers: List<FeatureProvider>,
        key: String,
        defaultValue: T,
        evaluationContext: EvaluationContext?,
        flagEval: FlagEval<T>
    ): ProviderEvaluation<T> {
        lastProviderNames = providers.map { it.metadata.name.orEmpty() }
        @Suppress("UNCHECKED_CAST")
        return returnValue as ProviderEvaluation<T>
    }
}