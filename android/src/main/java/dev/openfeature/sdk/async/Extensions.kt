package dev.openfeature.sdk.async

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.events.OpenFeatureEvents
import dev.openfeature.sdk.events.isProviderError
import dev.openfeature.sdk.events.isProviderReady
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
import java.lang.RuntimeException

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
        if (isProviderReady()) {
            this.emit(OpenFeatureEvents.ProviderReady)
        }
    }

internal fun FeatureProvider.observeProviderError() = observe<OpenFeatureEvents.ProviderError>()
    .onStart {
        if (isProviderError()) {
            this.emit(OpenFeatureEvents.ProviderError(RuntimeException())) // TODO Forward the correct error
        }
    }

inline fun <reified T : OpenFeatureEvents> OpenFeatureAPI.observeEvents(): Flow<T>? {
    return getProvider()?.observe<T>()
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