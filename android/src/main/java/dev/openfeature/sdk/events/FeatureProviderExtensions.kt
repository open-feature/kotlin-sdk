package dev.openfeature.sdk.events

import dev.openfeature.sdk.FeatureProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun FeatureProvider.observeProviderReady() = observe<OpenFeatureEvents.ProviderReady>()
    .onStart {
        if (getProviderStatus() == OpenFeatureEvents.ProviderReady) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }

internal fun FeatureProvider.observeProviderError() = observe<OpenFeatureEvents.ProviderError>()
    .onStart {
        val status = getProviderStatus()
        if (status is OpenFeatureEvents.ProviderError) {
            this.emit(status)
        }
    }

suspend fun FeatureProvider.awaitReadyOrError(
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) = suspendCancellableCoroutine { continuation ->
    val coroutineScope = CoroutineScope(dispatcher)
    coroutineScope.launch {
        observeProviderReady()
            .take(1)
            .collect {
                continuation.resumeWith(Result.success(Unit))
            }
    }

    coroutineScope.launch {
        observeProviderError()
            .take(1)
            .collect {
                continuation.resumeWith(Result.success(Unit))
            }
    }

    continuation.invokeOnCancellation {
        coroutineScope.cancel()
    }
}