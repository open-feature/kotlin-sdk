package dev.openfeature.sdk.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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

class EventHandler : EventObserver, EventsPublisher, ProviderStatus {
    private val sharedFlow: MutableSharedFlow<OpenFeatureEvents> = MutableSharedFlow()
    private val isProviderReady = MutableStateFlow(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        coroutineScope.launch {
            sharedFlow.collect {
                when (it) {
                    is OpenFeatureEvents.ProviderReady -> isProviderReady.value = true
                    is OpenFeatureEvents.ProviderShutDown -> {
                        isProviderReady.value = false
                        coroutineScope.cancel()
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

    override fun isProviderReady(): Boolean {
        return isProviderReady.value
    }

    override fun <T : OpenFeatureEvents> observe(kClass: KClass<T>): Flow<T> = sharedFlow
        .filterIsInstance(kClass)

    companion object {
        @Volatile
        private var instance: EventHandler? = null

        private fun getInstance() =
            instance ?: synchronized(this) {
                instance ?: create().also { instance = it }
            }

        fun eventsObserver(): EventObserver = getInstance()
        fun providerStatus(): ProviderStatus = getInstance()
        fun eventsPublisher(): EventsPublisher = getInstance()

        private fun create() = EventHandler()
    }
}