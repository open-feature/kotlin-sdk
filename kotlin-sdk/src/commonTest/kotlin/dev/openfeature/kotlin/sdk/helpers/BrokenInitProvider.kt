package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.FlagNotFoundError

class BrokenInitProvider(
    hooks: List<Hook<*>> = listOf(),
    metadata: ProviderMetadata = NamedMetadata("test")
) : TrackedProvider(hooks, metadata) {
    override fun unresolvable(key: String): Nothing = throw FlagNotFoundError(key)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        val error = OpenFeatureError.ProviderNotReadyError("test error from $this")
        emit(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = error.message,
                    errorCode = error.errorCode()
                )
            )
        )
        throw error
    }
}