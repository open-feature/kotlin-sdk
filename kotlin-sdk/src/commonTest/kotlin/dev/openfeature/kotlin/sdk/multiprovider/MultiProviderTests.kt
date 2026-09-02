package dev.openfeature.kotlin.sdk.multiprovider

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.ProviderStatusTracker
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale()
            )
        )
        val multi = MultiProvider(listOf(provider))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        // The last emitted event should be STALE given the sequence above
        val last = multi.observe().first()
        assertEquals(OpenFeatureProviderEvents.ProviderStale(), last)
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
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderReady()
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderStale()
            )
        )
        val c = FakeEventProvider(
            name = "C",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
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

        // Final aggregate status should be ERROR (C ends in ERROR; beats READY and STALE)
        val finalStatus = multi.status
        assertIs<OpenFeatureStatus.Error>(finalStatus)
        initJob.cancelAndJoin()
    }

    @Test
    fun emitsProviderErrorWhenFatalOverridesAll() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderReady()
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
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

        val finalStatus = multi.status
        val errStatus = assertIs<OpenFeatureStatus.Fatal>(finalStatus)
        assertIs<OpenFeatureError.ProviderFatalError>(errStatus.error)
        initJob.cancelAndJoin()
    }

    @Test
    fun errorOverridesReadyButStaleDoesNotOverrideError() = runTest {
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderReady()
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
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
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderStale()
            )
        )

        val multi = MultiProvider(listOf(a, b, c))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        val finalStatus = multi.status
        assertIs<OpenFeatureStatus.Error>(finalStatus)
        initJob.cancelAndJoin()
    }

    @Test
    fun notReadyOutRanksErrorAndStale() = runTest {
        // A never emits Ready/Error/Stale, so it stays at initial NOT_READY (per spec there is no PROVIDER_NOT_READY event)
        val a = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged()
            )
        )
        val b = FakeEventProvider(
            name = "B",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
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
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderStale()
            )
        )
        val multi = MultiProvider(listOf(a, b, c))

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        val finalStatus = multi.status
        assertIs<OpenFeatureStatus.NotReady>(finalStatus)
        initJob.cancelAndJoin()
    }

    @Test
    fun emitsEventsOnlyOnStatusChange() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderReady()
            )
        )
        val multi = MultiProvider(listOf(provider))

        val collected = mutableListOf<OpenFeatureProviderEvents>()
        val collectJob = launch { multi.observe().collect { collected.add(it) } }

        val initJob = launch { multi.initialize(null) }
        advanceUntilIdle()

        // A later transition is reported, an unchanged aggregate is not.
        provider.emit(OpenFeatureProviderEvents.ProviderStale())
        advanceUntilIdle()
        provider.emit(OpenFeatureProviderEvents.ProviderStale())
        advanceUntilIdle()

        collectJob.cancelAndJoin()
        initJob.cancelAndJoin()

        val nonConfig = collected.filter { it !is OpenFeatureProviderEvents.ProviderConfigurationChanged }
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReady(),
                OpenFeatureProviderEvents.ProviderStale()
            ),
            nonConfig
        )
    }

    @Test
    fun configurationChangedIsAlwaysEmitted() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(
                OpenFeatureProviderEvents.ProviderConfigurationChanged(),
                OpenFeatureProviderEvents.ProviderConfigurationChanged()
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
    fun aCancelledContextSetDoesNotLeaveTheAggregateReconciling() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(OpenFeatureProviderEvents.ProviderReady()),
            gateContextSet = true
        )
        val multi = MultiProvider(listOf(provider))
        multi.initialize(null)
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Ready, multi.status)

        val contextSet = launch { multi.onContextSet(null, ImmutableContext("ctx")) }
        provider.contextSetStarted.receive()
        assertEquals(OpenFeatureStatus.Reconciling, multi.status)

        contextSet.cancelAndJoin()
        advanceUntilIdle()

        // The tracker restores the status that preceded the reconciliation rather than stranding it.
        assertEquals(OpenFeatureStatus.Ready, multi.status)
    }

    @Test
    fun overlappingContextSetsReportReconcilingOnceAndOnlyTheLastOutcome() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(OpenFeatureProviderEvents.ProviderReady()),
            gateContextSet = true
        )
        val multi = MultiProvider(listOf(provider))
        multi.initialize(null)
        advanceUntilIdle()

        val collected = mutableListOf<OpenFeatureProviderEvents>()
        val collectJob = launch { multi.observe().collect { collected.add(it) } }
        advanceUntilIdle()
        collected.clear()

        val first = launch { multi.onContextSet(null, ImmutableContext("first")) }
        provider.contextSetStarted.receive()
        val second = launch { multi.onContextSet(null, ImmutableContext("second")) }
        provider.contextSetStarted.receive()

        provider.allowContextSetToComplete.send(Unit)
        first.join()
        advanceUntilIdle()
        // The first to terminate must not resolve a reconciliation the second is still running.
        assertEquals(OpenFeatureStatus.Reconciling, multi.status)

        provider.allowContextSetToComplete.send(Unit)
        second.join()
        advanceUntilIdle()
        collectJob.cancelAndJoin()

        assertEquals(OpenFeatureStatus.Ready, multi.status)
        assertEquals(
            listOf(
                OpenFeatureProviderEvents.ProviderReconciling::class,
                OpenFeatureProviderEvents.ProviderContextChanged::class
            ),
            collected.map { it::class }
        )
    }

    @Test
    fun anErrorAggregateCarriesTheTriggeringChildDetails() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(OpenFeatureProviderEvents.ProviderReady())
        )
        val multi = MultiProvider(listOf(provider))
        multi.initialize(null)
        advanceUntilIdle()

        val collected = mutableListOf<OpenFeatureProviderEvents>()
        val collectJob = launch { multi.observe().collect { collected.add(it) } }
        advanceUntilIdle()
        collected.clear()

        provider.emit(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    flagsChanged = setOf("a", "b"),
                    message = "child failed",
                    eventMetadata = mapOf("origin" to "A")
                )
            )
        )
        advanceUntilIdle()
        collectJob.cancelAndJoin()

        val reported = assertIs<OpenFeatureProviderEvents.ProviderError>(collected.single())
        assertEquals(setOf("a", "b"), reported.eventDetails?.flagsChanged)
        assertEquals(mapOf<String, Any>("origin" to "A"), reported.eventDetails?.eventMetadata)
    }

    @Test
    fun aChildFailingToInitializeDoesNotStopItsSiblings() = runTest {
        val failing = FakeEventProvider(
            name = "failing",
            initializeThrowable = OpenFeatureError.GeneralError("cannot start")
        )
        val healthy = FakeEventProvider(
            name = "healthy",
            eventsToEmitOnInit = listOf(OpenFeatureProviderEvents.ProviderReady())
        )
        val multi = MultiProvider(listOf(failing, healthy))

        multi.initialize(null)
        advanceUntilIdle()

        assertEquals(1, failing.initializeCalls)
        assertEquals(1, healthy.initializeCalls, "a failing sibling must not cancel this one")
        // The failing child reported nothing, so it is still not-ready and outranks the healthy one.
        assertEquals(OpenFeatureStatus.NotReady, multi.status)
    }

    @Test
    fun anAggregateReturningToNotReadyIsReported() = runTest {
        val provider = FakeEventProvider(
            name = "A",
            eventsToEmitOnInit = listOf(OpenFeatureProviderEvents.ProviderReady())
        )
        val multi = MultiProvider(listOf(provider))
        multi.initialize(null)
        advanceUntilIdle()
        assertEquals(OpenFeatureStatus.Ready, multi.status)

        // The child is taken down on its own, so the aggregate is no longer ready. There is no event
        // describing not-ready, so this is observable through the status rather than through observe().
        provider.shutdown()
        provider.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        advanceUntilIdle()

        assertEquals(OpenFeatureStatus.NotReady, multi.status)
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

    @Test
    fun initializeFunctionCompletesWhenObservingNeverCompletingFlows() = runTest {
        val fakeEventProvider = FakeEventProvider(name = "ok")

        // Should complete immediately
        withTimeout(1000) {
            val multi = MultiProvider(listOf(fakeEventProvider))
            multi.initialize(null)
        }
    }

    @Test
    fun trackCallIsTriggeredInChildProviders() {
        val fakeEventProvider1 = FakeEventProvider(name = "1")
        val fakeEventProvider2 = FakeEventProvider(name = "2")

        // When triggering tracking calls
        val multi = MultiProvider(listOf(fakeEventProvider1, fakeEventProvider2))
        multi.track("exposure", null, null)

        assertEquals(1, fakeEventProvider1.trackingCalls)
        assertEquals(1, fakeEventProvider2.trackingCalls)
    }

    @Test
    fun trackAggregatesErrorsAndReportsProviderNames() {
        val ok = FakeEventProvider(name = "ok")
        val bad1 = FakeEventProvider(name = "bad1", trackThrowable = IllegalStateException("track-fail1"))
        val bad2 = FakeEventProvider(name = null, trackThrowable = RuntimeException("track-fail2"))

        val multi = MultiProvider(listOf(ok, bad1, bad2))

        val error = assertFailsWith<OpenFeatureError.GeneralError> {
            multi.track("exposure", null, null)
        }

        val msg = error.message
        assertTrue(msg.contains("bad1: track-fail1"))
        assertTrue(msg.contains("<unnamed>: track-fail2"))

        assertEquals(2, error.suppressedExceptions.size)
        val suppressedMessages = error.suppressedExceptions.map { it.message ?: "" }
        assertTrue(suppressedMessages.any { it.contains("Provider 'bad1' tracking failed") })
        assertTrue(suppressedMessages.any { it.contains("Provider '<unnamed>' tracking failed") })
    }
}

// Helpers

private class FakeEventProvider(
    private val name: String?,
    private val eventsToEmitOnInit: List<OpenFeatureProviderEvents> = emptyList(),
    private val shutdownThrowable: Throwable? = null,
    private val trackThrowable: Throwable? = null,
    private val initializeThrowable: Throwable? = null,
    private val gateContextSet: Boolean = false
) : FeatureProvider {
    val contextSetStarted = Channel<Unit>(Channel.UNLIMITED)
    val allowContextSetToComplete = Channel<Unit>(Channel.UNLIMITED)
    override val hooks: List<Hook<*>> = emptyList()
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = this@FakeEventProvider.name
    }

    private val statusTracker = ProviderStatusTracker()

    override val status: OpenFeatureStatus get() = statusTracker.status

    override fun observe(): Flow<OpenFeatureProviderEvents> = statusTracker.observe()

    var initializeCalls: Int = 0
        private set
    var shutdownCalls: Int = 0
        private set
    var onContextSetCalls: Int = 0
        private set
    var trackingCalls: Int = 0
        private set

    override suspend fun initialize(initialContext: EvaluationContext?) {
        initializeCalls += 1
        // Emit any preconfigured events during initialize so MultiProvider observers receive them
        eventsToEmitOnInit.forEach { statusTracker.send(it) }
        initializeThrowable?.let { throw it }
    }

    fun emit(event: OpenFeatureProviderEvents) = statusTracker.send(event)

    override fun shutdown() {
        shutdownCalls += 1
        statusTracker.reset()
        shutdownThrowable?.let { throw it }
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        onContextSetCalls += 1
        if (gateContextSet) {
            contextSetStarted.send(Unit)
            allowContextSetToComplete.receive()
        }
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

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
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

    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?
    ) {
        trackingCalls++
        trackThrowable?.let { throw it }
    }
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