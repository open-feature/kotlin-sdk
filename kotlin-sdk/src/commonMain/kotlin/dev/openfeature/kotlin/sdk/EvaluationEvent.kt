package dev.openfeature.kotlin.sdk

/**
 * OpenTelemetry compatible telemetry signal for flag evaluations. Can be created by calling [createEvaluationEvent].
 *
 * See
 * [TELEMETRY_KEY],
 * [TELEMETRY_ERROR_CODE],
 * [TELEMETRY_VARIANT],
 * [TELEMETRY_CONTEXT_ID],
 * [TELEMETRY_ERROR_MSG],
 * [TELEMETRY_REASON],
 * [TELEMETRY_PROVIDER],
 * [TELEMETRY_FLAG_SET_ID],
 * [TELEMETRY_VERSION],
 * [TELEMETRY_FLAG_META_CONTEXT_ID],
 * [TELEMETRY_FLAG_META_FLAG_SET_ID],
 * [TELEMETRY_FLAG_META_VERSION],
 * [TELEMETRY_BODY] and
 * [FLAG_EVALUATION_EVENT_NAME]
 * for attribute and body keys.
 */
data class EvaluationEvent(
    val name: String,
    val attributes: Map<String, Any?>,
    val body: Map<String, Any?>
)