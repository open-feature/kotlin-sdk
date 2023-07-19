package dev.openfeature.sdk.async

import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.EventObserver
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.ProviderStatus
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

interface AsyncClient {
    fun observeBooleanValue(key: String, default: Boolean): Flow<Boolean>
    fun observeIntValue(key: String, default: Int): Flow<Int>
    fun observeStringValue(key: String, default: String): Flow<String>
}

internal class AsyncClientImpl(
    private val client: OpenFeatureClient,
    private val eventsObserver: EventObserver,
    private val providerStatus: ProviderStatus
) : AsyncClient {
    private fun <T> observeEvents(callback: () -> T) = eventsObserver
        .observe<OpenFeatureEvents.ProviderReady>()
        .onStart {
            if (providerStatus.isProviderReady()) {
                this.emit(OpenFeatureEvents.ProviderReady)
            }
        }
        .map { callback() }
        .distinctUntilChanged()

    override fun observeBooleanValue(key: String, default: Boolean) = observeEvents {
        client.getBooleanValue(key, default)
    }

    override fun observeIntValue(key: String, default: Int) = observeEvents {
        client.getIntegerValue(key, default)
    }

    override fun observeStringValue(key: String, default: String) = observeEvents {
        client.getStringValue(key, default)
    }
}