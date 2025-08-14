package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.ErrorCode

const val TELEMETRY_KEY = "feature_flag.key"
const val TELEMETRY_ERROR_CODE = "error.type"
const val TELEMETRY_VARIANT = "feature_flag.variant"
const val TELEMETRY_CONTEXT_ID = "feature_flag.context.id"
const val TELEMETRY_ERROR_MSG = "feature_flag.evaluation.error.message"
const val TELEMETRY_REASON = "feature_flag.evaluation.reason"
const val TELEMETRY_PROVIDER = "feature_flag.provider_name"
const val TELEMETRY_FLAG_SET_ID = "feature_flag.set.id"
const val TELEMETRY_VERSION = "feature_flag.version"

// Well-known flag metadata attributes for telemetry events.
// Specification: https://openfeature.dev/specification/appendix-d#flag-metadata
const val TELEMETRY_FLAG_META_CONTEXT_ID = "contextId"
const val TELEMETRY_FLAG_META_FLAG_SET_ID = "flagSetId"
const val TELEMETRY_FLAG_META_VERSION = "version"

// OpenTelemetry event body.
// Specification: https://opentelemetry.io/docs/specs/semconv/feature-flags/feature-flags-logs/
const val TELEMETRY_BODY = "value"

const val FLAG_EVALUATION_EVENT_NAME = "feature_flag.evaluation"

/**
 * Creates an [EvaluationEvent] from a flag evaluation. To be used inside a finally hook to provide an OpenTelemetry
 * compatible telemetry signal.
 */
fun <T> createEvaluationEvent(
    hookContext: HookContext<T>,
    flagEvaluationDetails: FlagEvaluationDetails<T>
): EvaluationEvent {
    val attributes = mutableMapOf<String, Any?>()
    val body = mutableMapOf<String, Any?>()
    attributes[TELEMETRY_KEY] = hookContext.flagKey
    attributes[TELEMETRY_PROVIDER] = hookContext.providerMetadata.name ?: ""
    attributes[TELEMETRY_REASON] = flagEvaluationDetails.reason?.lowercase() ?: Reason.UNKNOWN.name.lowercase()
    attributes[TELEMETRY_CONTEXT_ID] =
        flagEvaluationDetails.metadata.getString(TELEMETRY_FLAG_META_CONTEXT_ID) ?: hookContext.ctx?.getTargetingKey()
    flagEvaluationDetails.metadata.getString(TELEMETRY_FLAG_META_FLAG_SET_ID)?.let {
        attributes[TELEMETRY_FLAG_SET_ID] = it
    }
    flagEvaluationDetails.metadata.getString(TELEMETRY_FLAG_META_VERSION)?.let { attributes[TELEMETRY_VERSION] = it }

    val variant = flagEvaluationDetails.variant
    if (variant == null) {
        body[TELEMETRY_BODY] = flagEvaluationDetails.value
    } else {
        attributes[TELEMETRY_VARIANT] = variant
    }

    if (flagEvaluationDetails.reason == Reason.ERROR.name) {
        attributes[TELEMETRY_ERROR_CODE] = flagEvaluationDetails.errorCode ?: ErrorCode.GENERAL
        flagEvaluationDetails.errorMessage?.let { attributes[TELEMETRY_ERROR_MSG] = it }
    }

    return EvaluationEvent(
        FLAG_EVALUATION_EVENT_NAME,
        attributes,
        body
    )
}