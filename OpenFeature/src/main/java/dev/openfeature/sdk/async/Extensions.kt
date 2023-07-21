package dev.openfeature.sdk.async

import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.flow.onStart

fun OpenFeatureClient.toAsync(): AsyncClient {
    return AsyncClientImpl(this)
}

fun observeProviderReady() = EventHandler.eventsObserver()
    .observe<OpenFeatureEvents.ProviderReady>()
    .onStart {
        if (EventHandler.providerStatus().isProviderReady()) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }