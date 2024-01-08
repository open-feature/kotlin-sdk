package dev.openfeature.sdk.events

import dev.openfeature.sdk.FeatureProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

interface EventObserver {
    fun observe(): Flow<OpenFeatureEvents>
}

interface ProviderStatus {
    fun getProviderStatus(): OpenFeatureEvents
}

fun FeatureProvider.isProviderReady(): Boolean {
    val providerStatus = getProviderStatus()
    return providerStatus == OpenFeatureEvents.ProviderReady
}

fun FeatureProvider.isProviderError(): Boolean =
    getProviderStatus() is OpenFeatureEvents.ProviderError

interface EventsPublisher {
    fun publish(event: OpenFeatureEvents)
}

inline fun <reified T : OpenFeatureEvents> EventObserver.observe() = observe()
    .filterIsInstance<T>()

class EventHandler(dispatcher: CoroutineDispatcher) :
    EventObserver,
    EventsPublisher,
    ProviderStatus {
    private val sharedFlow: MutableSharedFlow<OpenFeatureEvents> = MutableSharedFlow()
    private val currentStatus: MutableStateFlow<OpenFeatureEvents> =
        MutableStateFlow(OpenFeatureEvents.ProviderShutDown)
    private val job = Job()
    private val coroutineScope = CoroutineScope(job + dispatcher)

    init {
        coroutineScope.launch {
            sharedFlow.collect {
                currentStatus.value = it
                when (it) {
                    is OpenFeatureEvents.ProviderShutDown -> {
                        job.cancelChildren()
                    }

                    else -> {
                        // do nothing
                    }
                }
            }
        }
    }

    override fun publish(event: OpenFeatureEvents) {
        coroutineScope.launch {
            sharedFlow.emit(event)
        }
    }

    override fun observe(): Flow<OpenFeatureEvents> = sharedFlow

    override fun getProviderStatus(): OpenFeatureEvents = currentStatus.value
}