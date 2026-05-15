package dev.openfeature.kotlin.sdk.telemetry

/**
 * Represents an evaluation event containing standard OTel flag mapping attributes.
 */
data class EvaluationEvent(
    val name: String,
    val attributes: Map<String, Any?>,
    val body: Map<String, Any?> = emptyMap()
)