package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiProviderTests {

    @Test
    fun uniqueChildNamesAreAssignedForDuplicates() {
        val p1 = FakeEventProvider(name = "Provider")
        val p2 = FakeEventProvider(name = "Provider")
        val p3 = FakeEventProvider(name = "ProviderNew")

        val multi = MultiProvider(listOf(p1, p2, p3))

        // All providers should be present as children
        assertEquals(3, multi.getProviderCount())

        // Original metadata should be keyed by unique child names
        val keys = multi.metadata.originalMetadata.keys
        assertTrue(keys.contains("Provider_1"))
        assertTrue(keys.contains("Provider_2"))
        assertTrue(keys.contains("ProviderNew"))
    }

    @Test
    fun metadataIncludesOriginalMetadataAndHandlesUnnamedProviders() {
        val named = FakeEventProvider(name = "A")
        val unnamed = FakeEventProvider(name = null)

        val multi = MultiProvider(listOf(named, unnamed))

        val original = multi.metadata.originalMetadata

        // Contains the named provider key mapping to some metadata
        assertTrue(original.containsKey("A"))
        assertNotNull(original["A"], "Original metadata should include entry for named provider")

        // Contains at least one generated key for unnamed providers
        val unnamedKey = original.keys.firstOrNull { it.startsWith("<unnamed>") }
        assertNotNull(original[unnamedKey], "Original metadata should include entry for unnamed provider")
    }

    @Test
    fun childProviderNamingIsStableAndSuffixedPerBaseNameInOrder() {
        val unnamed1 = FakeEventProvider(name = null)
        val x1 = FakeEventProvider(name = "X")
        val unnamed2 = FakeEventProvider(name = null)
        val x2 = FakeEventProvider(name = "X")
        val y = FakeEventProvider(name = "Y")

        val multi = MultiProvider(listOf(unnamed1, x1, unnamed2, x2, y))

        val keysInOrder = multi.metadata.originalMetadata.keys.toList()

        // Unnamed providers get "<unnamed>_1", "<unnamed>_2" in order of appearance
        // Duplicate named providers get suffixed per base name in order
        // Singletons keep their base name without suffix
        assertEquals(listOf("<unnamed>_1", "X_1", "<unnamed>_2", "X_2", "Y"), keysInOrder)
    }

    @Test
    fun forwardsLifecycleCallsToUnderlyingProviders() = runTest {
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
    fun observesEventsAndAppliesPrecedenceAfterConfigurationChange() = runTest {
        // Including ProviderConfigurationChanged first allows subsequent lower-precedence READY to emit
        val provider = FakeEventProvider(
            name = "p",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val multi = MultiProvider(listOf(provider))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        // The last emitted event should be STALE given the sequence above
        val last = multi.observe().first()
        assertEquals(OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails()), last)
        initJob.cancelAndJoin()
    }

    @Test
    fun usesStrategyForEvaluationsAndPreservesOrderIncludingDuplicates() {
        val p1 = FakeEventProvider(name = "A")
        val dup = FakeEventProvider(name = "A")
        val p2 = FakeEventProvider(name = "B")

        val recorder = RecordingStrategy(returnValue = ProviderEvaluation(true))
        val multi = MultiProvider(listOf(p1, dup, p2), strategy = recorder)

        val eval = multi.getBooleanEvaluation("flag", false, null)

        assertEquals(true, eval.value)
        // The strategy receives all providers in order; duplicates are preserved
        assertEquals(listOf("A", "A", "B"), recorder.lastProviderNames)
    }

    @Test
    fun aggregatesEventPrecedenceAcrossMultipleProviders() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val c = FakeEventProvider(
            name = "C",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderNotReady,
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(
                        message = "boom",
                        errorCode = dev.openfeature.kotlin.sdk.exceptions.ErrorCode.GENERAL
                    )
                )
            )
        )
        val multi = MultiProvider(listOf(a, b, c))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        // Final aggregate status should be ERROR (no providers remain NOT_READY)
        val finalStatus = multi.statusFlow.value
        assertIs<OpenFeatureStatus.Error>(finalStatus)
        initJob.cancelAndJoin()
    }

    @Test
    fun emitsProviderErrorWhenFatalOverridesAll() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(
                        message = "fatal",
                        errorCode = dev.openfeature.kotlin.sdk.exceptions.ErrorCode.PROVIDER_FATAL
                    )
                )
            )
        )
        val multi = MultiProvider(listOf(a, b))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        val finalStatus = multi.statusFlow.value
        val errStatus = assertIs<OpenFeatureStatus.Fatal>(finalStatus)
        assertIs<OpenFeatureError.ProviderFatalError>(errStatus.error)
        initJob.cancelAndJoin()
    }

    @Test
    fun errorOverridesReadyButStaleDoesNotOverrideError() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(
                        message = "oops",
                        errorCode = dev.openfeature.kotlin.sdk.exceptions.ErrorCode.GENERAL
                    )
                )
            )
        )
        val c = FakeEventProvider(
            name = "C",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            )
        )

        val multi = MultiProvider(listOf(a, b, c))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        val finalStatus = multi.statusFlow.value
        assertIs<OpenFeatureStatus.Error>(finalStatus)
        initJob.cancelAndJoin()
    }

    @Test
    fun notReadyOutRanksErrorAndStale() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderNotReady
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderError(
                    OpenFeatureProviderEvents.EventDetails(
                        message = "e",
                        errorCode = dev.openfeature.kotlin.sdk.exceptions.ErrorCode.GENERAL
                    )
                )
            )
        )
        val c = FakeEventProvider(
            name = "C",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val multi = MultiProvider(listOf(a, b, c))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        val finalStatus = multi.statusFlow.value
        assertIs<OpenFeatureStatus.NotReady>(finalStatus)
        initJob.cancelAndJoin()
    }

    @Test
    fun emitsEventsOnlyOnStatusChange() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val multi = MultiProvider(listOf(provider))

        val collected = mutableListOf<OpenFeatureProviderEvents>()
        val collectJob = launch { multi.observe().collect { collected.add(it) } }

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        collectJob.cancelAndJoin()
        initJob.cancelAndJoin()

        val nonConfig = collected.filter { it !is OpenFeatureProviderEvents.ProviderConfigurationChanged }
        // Should only emit Ready once (transition) and Stale once (transition)
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderStale(OpenFeatureProviderEvents.EventDetails())
            ),
            nonConfig
        )
    }

    @Test
    fun configurationChangedIsAlwaysEmitted() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails()),
                OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails())
            )
        )
        val multi = MultiProvider(listOf(provider))

        val collected = mutableListOf<OpenFeatureProviderEvents>()
        val collectJob = launch { multi.observe().collect { collected.add(it) } }

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        collectJob.cancelAndJoin()
        initJob.cancelAndJoin()

        // Only configuration changed events should have been emitted
        assertEquals(2, collected.size)
        assertTrue(collected.all { it is OpenFeatureProviderEvents.ProviderConfigurationChanged })
    }

    @Test
    fun shutdownAggregatesErrorsAndReportsProviderNames() {
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
) : MultiProvider.Strategy {
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