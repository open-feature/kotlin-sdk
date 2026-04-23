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

internal class DomainState(
    private val onStatusUpdate: suspend (FeatureProvider, OpenFeatureStatus) -> Unit = { _, _ -> }
) {
    var setProviderJob: Job? = null
    var setEvaluationContextJob: Job? = null
    val jobMutex = Mutex()

    val providerMutex = Mutex()
    val providersFlow: MutableStateFlow<FeatureProvider> = MutableStateFlow(NoOpProvider())
    val provider: FeatureProvider get() = providersFlow.value

    val contextMutex = Mutex()
    val ioMutex = Mutex()
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

    private val _eventsFlow = MutableSharedFlow<OpenFeatureProviderEvents>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val eventsFlow: Flow<OpenFeatureProviderEvents> get() = _eventsFlow

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
                            processProviderEvent(currentProvider, providerEvent)
                            emitEvent(providerEvent)
                        }
                    }
                }
                .launchIn(scope)
        }
    }

    private suspend fun processProviderEvent(eventProvider: FeatureProvider, event: OpenFeatureProviderEvents) {
        val status = when (event) {
            is OpenFeatureProviderEvents.ProviderReady -> OpenFeatureStatus.Ready
            is OpenFeatureProviderEvents.ProviderStale -> OpenFeatureStatus.Stale
            is OpenFeatureProviderEvents.ProviderError -> event.toOpenFeatureStatusError()
            else -> null
        }

        if (status != null) {
            emitStatus(status)
            onStatusUpdate(eventProvider, status)
        }
    }

    suspend fun emitStatus(status: OpenFeatureStatus) {
        _statusFlow.emit(status)
    }

    suspend fun emitEvent(event: OpenFeatureProviderEvents) {
        _eventsFlow.emit(event)
    }

    fun getStatus(): OpenFeatureStatus = _statusFlow.replayCache.firstOrNull() ?: OpenFeatureStatus.NotReady

    suspend fun resetAndGetProvider(): FeatureProvider {
        jobMutex.withLock {
            setProviderJob?.cancel(CancellationException("Provider set job was cancelled due to shutdown"))
            setEvaluationContextJob?.cancel(CancellationException("Set context job was cancelled due to shutdown"))
        }
        return providerMutex.withLock {
            domainScope?.cancel(CancellationException("DomainScope was cancelled due to shutdown"))
            domainScope = null
            currentDispatcher = null
            val current = provider
            providersFlow.value = NoOpProvider()
            _statusFlow.tryEmit(OpenFeatureStatus.NotReady)
            current
        }
    }
}

internal class ProviderRepository {
    private val onStatusUpdate: suspend (FeatureProvider, OpenFeatureStatus) -> Unit = { provider, status ->
        updateGlobalProviderStatus(provider, status)
    }

    private val defaultDomainState = DomainState(onStatusUpdate)
    internal val defaultStateFlow = MutableStateFlow(defaultDomainState)
    private val domainsFlow = MutableStateFlow<Map<String, DomainState>>(emptyMap())

    private val repositoryMutex = Mutex()
    private val referencesMutex = Mutex()
    private val providerReferences = mutableMapOf<FeatureProvider, Int>()
    private val providerInitMutexes = mutableMapOf<FeatureProvider, Mutex>()
    private val initializedProviders = mutableSetOf<FeatureProvider>()
    private val providerStatuses = mutableMapOf<FeatureProvider, OpenFeatureStatus>()

    suspend fun attachProvider(provider: FeatureProvider) {
        if (provider is NoOpProvider) return
        referencesMutex.withLock {
            val count = providerReferences[provider] ?: 0
            providerReferences[provider] = count + 1
        }
    }

    suspend fun detachProvider(provider: FeatureProvider): Boolean {
        if (provider is NoOpProvider) return false
        return referencesMutex.withLock {
            val count = (providerReferences[provider] ?: 0) - 1
            if (count <= 0) {
                providerReferences.remove(provider)
                providerInitMutexes.remove(provider)
                initializedProviders.remove(provider)
                providerStatuses.remove(provider)
                true
            } else {
                providerReferences[provider] = count
                false
            }
        }
    }

    suspend fun getInitMutex(provider: FeatureProvider): Mutex {
        return referencesMutex.withLock {
            providerInitMutexes.getOrPut(provider) { Mutex() }
        }
    }

    suspend fun isInitialized(provider: FeatureProvider): Boolean {
        return referencesMutex.withLock { initializedProviders.contains(provider) }
    }

    suspend fun markInitialized(provider: FeatureProvider) {
        referencesMutex.withLock { initializedProviders.add(provider) }
    }

    suspend fun getGlobalProviderStatus(provider: FeatureProvider): OpenFeatureStatus? {
        return referencesMutex.withLock { providerStatuses[provider] }
    }

    suspend fun updateGlobalProviderStatus(provider: FeatureProvider, status: OpenFeatureStatus) {
        referencesMutex.withLock { providerStatuses[provider] = status }
    }

    fun getState(domain: String? = null): DomainState {
        if (domain == null) return defaultDomainState
        return domainsFlow.value[domain] ?: defaultDomainState
    }

    suspend fun getOrCreateState(domain: String? = null): DomainState {
        if (domain == null) return defaultDomainState

        return domainsFlow.value[domain] ?: repositoryMutex.withLock {
            domainsFlow.value[domain] ?: DomainState(onStatusUpdate).also { newState ->
                domainsFlow.update { currentMap -> currentMap + (domain to newState) }
            }
        }
    }

    fun getStateFlow(domain: String?): Flow<DomainState> {
        if (domain == null) return defaultStateFlow
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
            val oldProvider = state.resetAndGetProvider()
            if (detachProvider(oldProvider)) {
                try {
                    oldProvider.shutdown()
                } catch (e: Exception) {
                    // Safely suppress exceptions crashing custom provider teardown loops
                }
            }
        }
    }
}