package dev.openfeature.sdk.async

import dev.openfeature.sdk.OpenFeatureClient
import dev.openfeature.sdk.Value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface AsyncClient {
    fun observeBooleanValue(key: String, default: Boolean): Flow<Boolean>
    fun observeIntValue(key: String, default: Int): Flow<Int>
    fun observeStringValue(key: String, default: String): Flow<String>
    fun observeDoubleValue(key: String, default: Double): Flow<Double>
    fun observeValue(key: String, default: Value): Flow<Value>
}

internal class AsyncClientImpl(
    private val client: OpenFeatureClient
) : AsyncClient {
    private fun <T> observeEvents(callback: () -> T) = observeProviderReady()
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

    override fun observeDoubleValue(key: String, default: Double): Flow<Double> = observeEvents {
        client.getDoubleValue(key, default)
    }

    override fun observeValue(key: String, default: Value): Flow<Value> = observeEvents {
        client.getObjectValue(key, default)
    }
}