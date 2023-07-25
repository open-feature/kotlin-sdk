package dev.openfeature.sdk.async

import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

fun OpenFeatureClient.toAsync(): AsyncClient {
    return AsyncClientImpl(this)
}

internal fun observeProviderReady() = EventHandler.eventsObserver()
    .observe<OpenFeatureEvents.ProviderReady>()
    .onStart {
        if (EventHandler.providerStatus().isProviderReady()) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }

suspend fun awaitProviderReady() = suspendCancellableCoroutine { continuation ->
    val coroutineScope = CoroutineScope(Dispatchers.IO)
    coroutineScope.launch {
        observeProviderReady()
            .take(1)
            .collect {
                continuation.resumeWith(Result.success(Unit))
            }
    }

    coroutineScope.launch {
        EventHandler.eventsObserver()
            .observe<OpenFeatureEvents.ProviderError>()
            .take(1)
            .collect {
                continuation.resumeWith(Result.failure(it.error))
            }
    }

    continuation.invokeOnCancellation {
        coroutineScope.cancel()
    }
}