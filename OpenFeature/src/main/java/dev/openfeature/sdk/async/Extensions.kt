package dev.openfeature.sdk.async

import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

fun OpenFeatureClient.toAsync(): AsyncClient? {
    val provider = OpenFeatureAPI.getProvider()
    return provider?.let {
        AsyncClientImpl(
            this,
            it
        )
    }
}

suspend fun OpenFeatureAPI.setProviderAndWait(
    provider: FeatureProvider,
    dispatcher: CoroutineDispatcher
) {
    setProvider(provider)
    provider.awaitReady(dispatcher)
}

internal fun FeatureProvider.observeProviderReady() = observe<OpenFeatureEvents.ProviderReady>()
    .onStart {
        if (isProviderReady()) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }

inline fun <reified T : OpenFeatureEvents> OpenFeatureAPI.observeEvents(): Flow<T>? {
    return getProvider()?.observe<T>()
}

suspend fun FeatureProvider.awaitReady(
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
        observe<OpenFeatureEvents.ProviderError>()
            .take(1)
            .collect {
                continuation.resumeWith(Result.failure(it.error))
            }
    }

    continuation.invokeOnCancellation {
        coroutineScope.cancel()
    }
}