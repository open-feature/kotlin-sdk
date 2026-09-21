package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.ProviderEvaluation

class RecordingBooleanProvider(
    name: String,
    private val behavior: () -> ProviderEvaluation<Boolean>
) : TrackedProvider(metadata = NamedMetadata(name)) {
    var booleanEvalCalls: Int = 0
        private set

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        booleanEvalCalls += 1
        return behavior()
    }
}