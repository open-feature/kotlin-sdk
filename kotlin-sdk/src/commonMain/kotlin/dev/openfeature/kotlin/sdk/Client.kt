package dev.openfeature.kotlin.sdk

import kotlinx.coroutines.flow.Flow

interface Client : Features, Tracking {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>
    val statusFlow: Flow<OpenFeatureStatus>

    fun addHooks(hooks: List<Hook<*>>)
}