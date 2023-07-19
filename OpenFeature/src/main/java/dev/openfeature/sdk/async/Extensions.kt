package dev.openfeature.sdk.async

import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.EventObserver
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.ProviderStatus
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.flow.onStart

fun OpenFeatureClient.toAsync(): AsyncClient {
    val eventsObserver: EventObserver = EventHandler.eventsObserver()
    val providerStatus: ProviderStatus = EventHandler.providerStatus()

    return AsyncClientImpl(
        this,
        eventsObserver,
        providerStatus
    )
}

fun observeProviderStatus() = observeProviderEvents()
    .observe<OpenFeatureEvents.ProviderReady>()
    .onStart {
        if (EventHandler.providerStatus().isProviderReady()) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }

fun observeProviderEvents() = EventHandler.eventsObserver()