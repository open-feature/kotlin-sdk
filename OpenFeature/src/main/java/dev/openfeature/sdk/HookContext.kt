package dev.openfeature.sdk

data class HookContext<T>(
    var flagKey: String,
    val type: FlagValueType,
    var defaultValue: T,
    var ctx: EvaluationContext?,
    var clientMetadata: ClientMetadata?,
    var providerMetadata: ProviderMetadata
)