package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatusError
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

class DomainState {
    var setProviderJob: Job? = null
    var setEvaluationContextJob: Job? = null

    val providerMutex = Mutex()
    val providersFlow: MutableStateFlow<FeatureProvider> = MutableStateFlow(NoOpProvider())
    val provider: FeatureProvider get() = providersFlow.value
    var context: EvaluationContext? = null
    var mergedContext: EvaluationContext? = null

    val _statusFlow: MutableSharedFlow<OpenFeatureStatus> =
        MutableSharedFlow<OpenFeatureStatus>(
            replay = 1,
            extraBufferCapacity = 5,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        ).apply {
            tryEmit(OpenFeatureStatus.NotReady)
        }

    val statusFlow: Flow<OpenFeatureStatus> get() = _statusFlow.distinctUntilChanged()

    private var domainScope: CoroutineScope? = null

    @Volatile
    private var currentDispatcher: CoroutineDispatcher? = null

    suspend fun initializeListener(dispatcher: CoroutineDispatcher) {
        providerMutex.withLock {
            currentDispatcher = dispatcher

            if (domainScope != null) return@withLock
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            domainScope = scope

            providersFlow
                .flatMapLatest { currentProvider ->
                    currentProvider.observe()
                        .retryWhen { cause, _ ->
                            emit(
                                OpenFeatureProviderEvents.ProviderError(
                                    OpenFeatureProviderEvents.EventDetails(
                                        message = cause.message ?: "Provider observe() crashed",
                                        errorCode = ErrorCode.GENERAL
                                    )
                                )
                            )
                            delay(3000L)
                            true
                        }
                        .map { event -> currentProvider to event }
                }
                .onEach { (currentProvider, providerEvent) ->
                    val activeDispatcher = currentDispatcher ?: dispatcher
                    withContext(activeDispatcher) {
                        if (providersFlow.value === currentProvider) {
                            processProviderEvent(providerEvent)
                        }
                    }
                }
                .launchIn(scope)
        }
    }

    private suspend fun processProviderEvent(event: OpenFeatureProviderEvents) {
        when (event) {
            is OpenFeatureProviderEvents.ProviderReady -> emitStatus(OpenFeatureStatus.Ready)
            is OpenFeatureProviderEvents.ProviderStale -> emitStatus(OpenFeatureStatus.Stale)
            is OpenFeatureProviderEvents.ProviderError -> emitStatus(event.toOpenFeatureStatusError())
            else -> { // All other states should not be emitted from here
            }
        }
    }

    suspend fun emitStatus(status: OpenFeatureStatus) {
        _statusFlow.emit(status)
    }

    fun getStatus(): OpenFeatureStatus = _statusFlow.replayCache.firstOrNull() ?: OpenFeatureStatus.NotReady

    suspend fun shutdown() {
        setProviderJob?.cancel(CancellationException("Provider set job was cancelled due to shutdown"))
        setEvaluationContextJob?.cancel(CancellationException("Set context job was cancelled due to shutdown"))
        providerMutex.withLock {
            domainScope?.cancel(CancellationException("DomainScope was cancelled due to shutdown"))
            domainScope = null
            currentDispatcher = null
        }
        provider.shutdown()
        providersFlow.value = NoOpProvider()
        _statusFlow.tryEmit(OpenFeatureStatus.NotReady)
    }
}

class ProviderRepository {
    private val defaultDomainState = DomainState()
    private val domainsFlow = MutableStateFlow<Map<String, DomainState>>(emptyMap())
    private val repositoryMutex = Mutex()

    fun getState(domain: String? = null): DomainState {
        if (domain == null) return defaultDomainState
        return domainsFlow.value[domain] ?: defaultDomainState
    }

    suspend fun getOrCreateState(domain: String? = null): DomainState {
        if (domain == null) return defaultDomainState

        return domainsFlow.value[domain] ?: repositoryMutex.withLock {
            domainsFlow.value[domain] ?: DomainState().also { newState ->
                domainsFlow.update { currentMap -> currentMap + (domain to newState) }
            }
        }
    }

    fun getStateFlow(domain: String?): Flow<DomainState> {
        if (domain == null) return MutableStateFlow(defaultDomainState)
        return domainsFlow.map { it[domain] ?: defaultDomainState }.distinctUntilChanged()
    }

    fun getAllStates(): List<DomainState> {
        return listOf(defaultDomainState) + domainsFlow.value.values.toList()
    }

    suspend fun clearAll() {
        // Evaluate and detach existing states safely
        val allStatesToShutdown = repositoryMutex.withLock {
            val all = getAllStates()
            domainsFlow.value = emptyMap()
            all
        }

        // Shutdown cleanly outside the repository lock to prevent lock-inversion deadlocks!
        allStatesToShutdown.forEach { state ->
            state.shutdown()
        }
    }
}