package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Adapts a plain [FeatureProvider] to [StateManagingProvider]
 * by deriving [status] from [FeatureProvider.observe]
 * while preserving legacy initialization and context
 * semantics (errors surface on [status] instead of throwing).
 *
 * [FeatureProvider] members (evaluations, [Hook]s, [ProviderMetadata], [track], etc.) delegate to
 * [inner]; lifecycle methods below are wrapped for status and error behavior.
 */
internal class LegacyFeatureProviderAdapter(
    val inner: FeatureProvider,
    private val eventDispatcher: CoroutineDispatcher
) : StateManagingProvider,
    FeatureProvider by inner {

    private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
    override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + eventDispatcher)
    private var observeJob: Job? = null

    override fun observe(): Flow<OpenFeatureProviderEvents> = inner.observe()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        observeJob?.cancel(CancellationException("Provider job was cancelled due to new provider"))
        _status.value = OpenFeatureStatus.NotReady
        observeJob = scope.launch {
            inner.observe().collect { event ->
                event.toOpenFeatureStatus()?.let { _status.value = it }
            }
        }
        try {
            inner.initialize(initialContext)
            _status.value = OpenFeatureStatus.Ready
        } catch (e: Throwable) {
            handleError(e)
        }
    }

    /**
     * [inner] is shut down first while the [observeJob] is still running so a provider can emit
     * a final [OpenFeatureProviderEvents] on [FeatureProvider.observe] and this adapter can still
     * apply [toOpenFeatureStatus] to [_status]. The observe job and [scope] (including
     * [SupervisorJob]) are cancelled in [finally] so cleanup and resource release always run.
     */
    override fun shutdown() {
        try {
            inner.shutdown()
            _status.value = OpenFeatureStatus.NotReady
        } catch (e: Throwable) {
            handleError(e)
        } finally {
            observeJob?.cancel(CancellationException("Provider event observe job was cancelled due to shutdown"))
            observeJob = null
            scope.cancel(
                CancellationException("LegacyFeatureProviderAdapter scope cancelled due to shutdown")
            )
        }
    }

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        try {
            _status.value = OpenFeatureStatus.Reconciling
            // MutableStateFlow conflates rapid updates; yield so [status] collectors see Reconciling
            // before Ready. and avoid flag evaluation against an inconsistent context
            yield()
            inner.onContextSet(oldContext, newContext)
            _status.value = OpenFeatureStatus.Ready
        } catch (e: Throwable) {
            handleError(e)
        }
    }

    private fun handleError(e: Throwable) {
        when (e) {
            is CancellationException -> { /* Cancelled by design - not an error */ }
            is OpenFeatureError -> _status.value = OpenFeatureStatus.Error(e)
            else -> _status.value = OpenFeatureStatus.Error(
                OpenFeatureError.GeneralError(e.message ?: "Unknown error")
            )
        }
    }
}