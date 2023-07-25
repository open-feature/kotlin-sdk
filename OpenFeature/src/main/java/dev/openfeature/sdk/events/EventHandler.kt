package dev.openfeature.sdk.events

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

interface ProviderStatus {
    fun isProviderReady(): Boolean
}

interface EventObserver {
    fun <T : OpenFeatureEvents> observe(kClass: KClass<T>): Flow<T>
}

interface EventsPublisher {
    fun publish(event: OpenFeatureEvents)
}

inline fun <reified T : OpenFeatureEvents> EventObserver.observe() = observe(T::class)

class EventHandler(dispatcher: CoroutineDispatcher) : EventObserver, EventsPublisher, ProviderStatus {
    private val sharedFlow: MutableSharedFlow<OpenFeatureEvents> = MutableSharedFlow()
    private val isProviderReady = MutableStateFlow(false)
    private val job = Job()
    private val coroutineScope = CoroutineScope(job + dispatcher)

    init {
        coroutineScope.launch {
            sharedFlow.collect {
                when (it) {
                    is OpenFeatureEvents.ProviderReady -> isProviderReady.value = true
                    is OpenFeatureEvents.ProviderShutDown -> {
                        isProviderReady.value = false
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

    override fun <T : OpenFeatureEvents> observe(kClass: KClass<T>): Flow<T> = sharedFlow
        .filterIsInstance(kClass)

    override fun isProviderReady(): Boolean {
        return isProviderReady.value
    }

    companion object {
        @Volatile
        private var instance: EventHandler? = null

        private fun getInstance(dispatcher: CoroutineDispatcher) =
            instance ?: synchronized(this) {
                instance ?: create(dispatcher).also { instance = it }
            }

        fun eventsObserver(dispatcher: CoroutineDispatcher = Dispatchers.IO): EventObserver =
            getInstance(dispatcher)
        internal fun providerStatus(dispatcher: CoroutineDispatcher = Dispatchers.IO): ProviderStatus =
            getInstance(dispatcher)
        fun eventsPublisher(dispatcher: CoroutineDispatcher = Dispatchers.IO): EventsPublisher =
            getInstance(dispatcher)

        private fun create(dispatcher: CoroutineDispatcher) = EventHandler(dispatcher)
    }
}