package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.LegacyMinimalProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [StateManagingProvider]: status from [StateManagingProvider.status],
 * init wait on status, [FeatureProvider.observe] for handlers, and swap from legacy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagingProviderStatusTests {

    @BeforeTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun smp_noop_is_state_managing_and_reports_ready_after_init() = runTest {
        val provider = NoOpProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        assertEquals(OpenFeatureStatus.Ready, provider.status.value)
    }

    @Test
    fun smp_statusFlow_tracks_provider_status() = runTest {
        val seen = mutableListOf<OpenFeatureStatus>()
        val job = launch {
            OpenFeatureAPI.statusFlow.collect { seen.add(it) }
        }

        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        waitAssert { assertTrue(seen.any { it is OpenFeatureStatus.Ready }, "expected Ready in $seen") }

        OpenFeatureAPI.clearProvider()
        waitAssert { assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus()) }
        waitAssert {
            assertTrue(seen.any { it is OpenFeatureStatus.NotReady }, "expected NotReady in $seen")
        }

        job.cancelAndJoin()
    }

    @Test
    fun smp_observe_delivers_provider_ready_after_setProviderAndWait() = runTest {
        val events = mutableListOf<OpenFeatureProviderEvents.ProviderReady>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderReady>().collect {
                events.add(it)
            }
        }

        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        waitAssert { assertTrue(events.isNotEmpty()) }

        job.cancelAndJoin()
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun smp_context_set_updates_status_via_provider() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider(), initialContext = ImmutableContext("a"))
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }

        OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("b"))
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
    }

    @Test
    fun smp_after_legacy_swap_status_comes_from_smp_instance() = runTest {
        OpenFeatureAPI.setProviderAndWait(LegacyMinimalProvider())
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        assertTrue(OpenFeatureAPI.getProvider() !is StateManagingProvider)

        val noop = NoOpProvider()
        OpenFeatureAPI.setProviderAndWait(noop)
        waitAssert { assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus()) }
        assertTrue(OpenFeatureAPI.getProvider() is StateManagingProvider)
        assertEquals(OpenFeatureStatus.Ready, noop.status.value)
    }

    @Test
    fun smp_inline_provider_waits_until_status_leaves_not_ready() = runTest {
        val impl = object : StateManagingProvider {
            override val hooks: List<Hook<*>> = listOf()
            override val metadata: ProviderMetadata = object : ProviderMetadata {
                override val name: String? = "inline-smp"
            }
            private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
            override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()
            private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

            override suspend fun initialize(initialContext: EvaluationContext?) {
                _status.value = OpenFeatureStatus.Ready
                events.emit(OpenFeatureProviderEvents.ProviderReady())
            }

            override fun shutdown() {
                _status.value = OpenFeatureStatus.NotReady
                events.tryEmit(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(
                            message = "shut down",
                            errorCode = ErrorCode.PROVIDER_NOT_READY
                        )
                    )
                )
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
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

        OpenFeatureAPI.setProviderAndWait(impl)
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun smp_setProviderAndWait_propagates_when_initialize_throws_without_leaving_not_ready() = runTest {
        val impl = object : StateManagingProvider {
            override val hooks: List<Hook<*>> = listOf()
            override val metadata: ProviderMetadata = object : ProviderMetadata {
                override val name: String? = "init-throws-smp"
            }
            private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
            override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()
            private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

            override suspend fun initialize(initialContext: EvaluationContext?) {
                throw OpenFeatureError.GeneralError("init failed")
            }

            override fun shutdown() {
                _status.value = OpenFeatureStatus.NotReady
                events.tryEmit(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(
                            message = "shut down",
                            errorCode = ErrorCode.PROVIDER_NOT_READY
                        )
                    )
                )
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
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

        assertFailsWith<OpenFeatureError.GeneralError> {
            OpenFeatureAPI.setProviderAndWait(impl)
        }
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        assertEquals(OpenFeatureStatus.NotReady, impl.status.value)
    }

    @Test
    fun smp_setProviderAndWait_times_out_when_initialize_never_moves_status_off_not_ready() = runTest {
        val impl = object : StateManagingProvider {
            override val hooks: List<Hook<*>> = listOf()
            override val metadata: ProviderMetadata = object : ProviderMetadata {
                override val name: String? = "init-hangs-smp"
            }
            private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
            override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()
            private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

            override suspend fun initialize(initialContext: EvaluationContext?) {
                // Leaves [status] at NotReady and emits no Ready/Error — SDK waits forever on status.
            }

            override fun shutdown() {
                _status.value = OpenFeatureStatus.NotReady
                events.tryEmit(
                    OpenFeatureProviderEvents.ProviderError(
                        OpenFeatureProviderEvents.EventDetails(
                            message = "shut down",
                            errorCode = ErrorCode.PROVIDER_NOT_READY
                        )
                    )
                )
            }

            override suspend fun onContextSet(
                oldContext: EvaluationContext?,
                newContext: EvaluationContext
            ) {
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

        launch {
            advanceTimeBy(600)
        }
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(500) {
                OpenFeatureAPI.setProviderAndWait(impl)
            }
        }
        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
    }
}