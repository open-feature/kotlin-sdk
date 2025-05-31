package dev.openfeature.kotlin.sdk

data class HookContext<T>(
    val flagKey: String,
    val type: FlagValueType,
    val defaultValue: T,
    val ctx: EvaluationContext?,
    val clientMetadata: ClientMetadata?,
    val providerMetadata: ProviderMetadata
)