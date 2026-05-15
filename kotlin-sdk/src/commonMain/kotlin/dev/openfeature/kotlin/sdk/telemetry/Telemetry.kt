package dev.openfeature.kotlin.sdk.telemetry

import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode

/**
 * The Telemetry object provides constants and utilities for creating OpenTelemetry compliant
 * evaluation events in alignment with OpenFeature Appendix D semantics.
 */
object Telemetry {

    // OTEL Semantic Convention keys for feature flag evaluation records
    const val TELEMETRY_KEY = "feature_flag.key"
    const val TELEMETRY_PROVIDER = "feature_flag.provider.name"
    const val TELEMETRY_REASON = "feature_flag.result.reason"
    const val TELEMETRY_VARIANT = "feature_flag.result.variant"
    const val TELEMETRY_VALUE = "feature_flag.result.value"
    const val TELEMETRY_CONTEXT_ID = "feature_flag.context.id"
    const val TELEMETRY_FLAG_SET_ID = "feature_flag.set.id"
    const val TELEMETRY_VERSION = "feature_flag.version"
    const val TELEMETRY_ERROR_CODE = "error.type"
    const val TELEMETRY_ERROR_MSG = "feature_flag.evaluation.error.message"

    // OpenFeature internal metadata keys matching Spec standard mapping boundaries
    const val TELEMETRY_FLAG_META_CONTEXT_ID = "contextId"
    const val TELEMETRY_FLAG_META_FLAG_SET_ID = "flagSetId"
    const val TELEMETRY_FLAG_META_VERSION = "version"

    const val FLAG_EVALUATION_EVENT_NAME = "feature_flag.evaluation"

    /**
     * Creates an OpenTelemetry compliant EvaluationEvent out of standard Evaluation details.
     */
    fun <T> createEvaluationEvent(
        hookContext: HookContext<T>,
        evaluationDetails: FlagEvaluationDetails<T>
    ): EvaluationEvent {
        val attributes = mutableMapOf<String, Any?>()
        val body = mutableMapOf<String, Any?>()

        // Required telemetry attributes
        attributes[TELEMETRY_KEY] = hookContext.flagKey
        attributes[TELEMETRY_PROVIDER] = hookContext.providerMetadata.name

        // Reason (Conditionally Required / Recommended)
        attributes[TELEMETRY_REASON] = evaluationDetails.reason?.lowercase() ?: Reason.UNKNOWN.name.lowercase()

        // Variant
        val variant = evaluationDetails.variant
        if (variant != null) {
            attributes[TELEMETRY_VARIANT] = variant
        }

        // Value (Recommended even when Variant is present)
        attributes[TELEMETRY_VALUE] = unwrapValue(evaluationDetails.value)

        // Context ID
        val contextId = evaluationDetails.metadata.getString(TELEMETRY_FLAG_META_CONTEXT_ID)
            ?: hookContext.ctx?.getTargetingKey()
        if (!contextId.isNullOrEmpty()) {
            attributes[TELEMETRY_CONTEXT_ID] = contextId
        }

        // Flag Set ID
        val setId = evaluationDetails.metadata.getString(TELEMETRY_FLAG_META_FLAG_SET_ID)
        if (setId != null) {
            attributes[TELEMETRY_FLAG_SET_ID] = setId
        }

        // Version
        val version = evaluationDetails.metadata.getString(TELEMETRY_FLAG_META_VERSION)
        if (version != null) {
            attributes[TELEMETRY_VERSION] = version
        }

        // Error State mapping
        if (evaluationDetails.reason.equals(Reason.ERROR.name, ignoreCase = true)) {
            attributes[TELEMETRY_ERROR_CODE] = evaluationDetails.errorCode?.name?.lowercase()
                ?: ErrorCode.GENERAL.name.lowercase()
            val errorMessage = evaluationDetails.errorMessage
            if (errorMessage != null) {
                attributes[TELEMETRY_ERROR_MSG] = errorMessage
            }
        }

        return EvaluationEvent(FLAG_EVALUATION_EVENT_NAME, attributes, body)
    }

    /**
     * Recursively unwraps structurally-typed OpenFeature [Value] allocations organically down
     * strictly representing their primitive equivalents mapping gracefully for OTEL.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun unwrapValue(value: Any?): Any? {
        return when (value) {
            is Value.Null -> null
            is Value.String -> value.string
            is Value.Boolean -> value.boolean
            is Value.Integer -> value.integer
            is Value.Double -> value.double
            is Value.Instant -> value.instant.toString() // ISO 8601 string
            is Value.List -> value.list.map { unwrapValue(it) }
            is Value.Structure -> value.structure.mapValues { (_, v) -> unwrapValue(v) }
            else -> value // Already a generic type primitive like standard Boolean/String evaluations
        }
    }
}