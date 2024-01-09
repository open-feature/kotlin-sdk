package dev.openfeature.sdk.async

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.observe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
    dispatcher: CoroutineDispatcher,
    initialContext: EvaluationContext? = null
) {
    setProvider(provider, initialContext)
    provider.awaitReadyOrError(dispatcher)
}

internal fun FeatureProvider.observeProviderReady() = observe<OpenFeatureEvents.ProviderReady>()
    .onStart {
        if (getProviderStatus() == OpenFeatureEvents.ProviderReady) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }

/*
Observe events from currently configured Provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun OpenFeatureAPI.observeEvents(): Flow<OpenFeatureEvents> {
    return sharedProvidersFlow.flatMapLatest { provider ->
        provider.observe()
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