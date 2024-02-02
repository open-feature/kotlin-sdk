package dev.openfeature.sdk.events

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
        MutableStateFlow(OpenFeatureEvents.ProviderNotReady)
    private val job = Job()
    private val coroutineScope = CoroutineScope(job + dispatcher)

    init {
        coroutineScope.launch {
            sharedFlow.collect {
                currentStatus.value = it
                when (it) {
                    is OpenFeatureEvents.ProviderNotReady -> {
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