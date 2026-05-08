package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import dev.openfeature.kotlin.sdk.Reason as EvaluationReason

private val providerMetadata = object : ProviderMetadata {
    override val name: String
        get() = "Provider name"
}

private fun <T> createGenericTestEvent(
    flagKey: String = "flag key",
    targetingKey: String = "targeting key",
    testProviderMetadata: ProviderMetadata = providerMetadata,
    reason: String? = null,
    errorCode: ErrorCode? = null,
    errorMessage: String? = null,
    variant: String? = null,
    value: T,
    defaultValue: T,
    flagValueType: FlagValueType,
    metadata: EvaluationMetadata? = null
): EvaluationEvent {
    val ctx = ImmutableContext(targetingKey)
    val hookContext = HookContext<T>(
        flagKey,
        flagValueType,
        defaultValue,
        ctx,
        null,
        testProviderMetadata
    )
    val providerEvaluation = if (metadata != null) {
        ProviderEvaluation<T>(
            value = value,
            variant = variant,
            reason = reason,
            errorCode = errorCode,
            errorMessage = errorMessage,
            metadata = metadata
        )
    } else {
        ProviderEvaluation<T>(
            value = value,
            variant = variant,
            reason = reason,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }
    val flagEvaluationDetails = FlagEvaluationDetails.from(providerEvaluation, flagKey)
    return createEvaluationEvent(hookContext, flagEvaluationDetails)
}

private fun createTestEvent(
    flagKey: String = "flag key",
    targetingKey: String = "targeting key",
    testProviderMetadata: ProviderMetadata = providerMetadata,
    reason: String? = null,
    errorCode: ErrorCode? = null,
    errorMessage: String? = null,
    variant: String? = null,
    value: String = "value",
    metadata: EvaluationMetadata? = null
): EvaluationEvent = createGenericTestEvent(
    flagKey = flagKey,
    targetingKey = targetingKey,
    testProviderMetadata = testProviderMetadata,
    reason = reason,
    errorCode = errorCode,
    errorMessage = errorMessage,
    variant = variant,
    value = value,
    defaultValue = "default",
    flagValueType = FlagValueType.STRING,
    metadata = metadata
)

class TelemetryTest {

    @Test
    fun `flagKey is set correctly`() {
        val flagKey = "custom flag key"
        val evaluationEvent = createTestEvent(flagKey = flagKey)

        assertEquals(flagKey, evaluationEvent.attributes[TELEMETRY_KEY])
    }

    class ProviderName {
        @Test
        fun `provider name is set correctly`() {
            val evaluationEvent = createTestEvent()

            assertEquals(providerMetadata.name, evaluationEvent.attributes[TELEMETRY_PROVIDER])
        }

        @Test
        fun `provider name is omitted when not available`() {
            val evaluationEvent = createTestEvent(
                testProviderMetadata = object : ProviderMetadata {
                    override val name: String?
                        get() = null
                }
            )

            assertFalse(evaluationEvent.attributes.containsKey(TELEMETRY_PROVIDER))
        }

        @Test
        fun `provider name is omitted when empty string`() {
            val evaluationEvent = createTestEvent(
                testProviderMetadata = object : ProviderMetadata {
                    override val name: String
                        get() = ""
                }
            )

            assertFalse(evaluationEvent.attributes.containsKey(TELEMETRY_PROVIDER))
        }
    }

    class Reason {
        @Test
        fun `create EvaluationEvent with UNKNOWN reason if reason is null`() {
            val evaluationEvent = createTestEvent(reason = null)

            assertEquals(EvaluationReason.UNKNOWN.name.lowercase(), evaluationEvent.attributes[TELEMETRY_REASON])
        }

        @Test
        fun `create EvaluationEvent with correct reason if reason is set`() {
            val evaluationEvent = createTestEvent(reason = EvaluationReason.TARGETING_MATCH.name.lowercase())

            assertEquals(
                EvaluationReason.TARGETING_MATCH.name.lowercase(),
                evaluationEvent.attributes[TELEMETRY_REASON]
            )
        }

        @Test
        fun `reason is safely lowercased regardless of input casing`() {
            val evaluationEvent = createTestEvent(reason = "StAte_CaChe_MaTch")

            assertEquals(
                "state_cache_match",
                evaluationEvent.attributes[TELEMETRY_REASON]
            )
        }
    }

    class ContextId {
        @Test
        fun `contextId is taken from evaluation metadata when available`() {
            val contextId = "contextId metadata"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("contextId", contextId)))
            val evaluationEvent = createTestEvent(
                targetingKey = "targeting key",
                metadata = evaluationMetadata
            )

            assertEquals(contextId, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
            assertNotEquals("targeting key", evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
        }

        @Test
        fun `contextId is taken from ctx when evaluation metadata is not available`() {
            val targetingKey = "targeting key"
            val evaluationEvent = createTestEvent(targetingKey = targetingKey)

            assertEquals(targetingKey, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
        }

        @Test
        fun `contextId is omitted when both evaluation metadata and ctx targetingKey are null or empty`() {
            val evaluationEvent = createTestEvent(targetingKey = "")

            assertFalse(evaluationEvent.attributes.containsKey(TELEMETRY_CONTEXT_ID))
        }
    }

    class FlagSetId {
        @Test
        fun `flagSetId is set correctly when available`() {
            val flagSetId = "flag set id"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("flagSetId", flagSetId)))
            val evaluationEvent = createTestEvent(metadata = evaluationMetadata)

            assertEquals(flagSetId, evaluationEvent.attributes[TELEMETRY_FLAG_SET_ID])
        }

        @Test
        fun `flagSetId is not set when not available`() {
            val evaluationEvent = createTestEvent()

            assertNull(evaluationEvent.attributes[TELEMETRY_FLAG_SET_ID])
        }
    }

    class Version {
        @Test
        fun `version is set correctly when available`() {
            val version = "v1.0.0"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("version", version)))
            val evaluationEvent = createTestEvent(metadata = evaluationMetadata)

            assertEquals(version, evaluationEvent.attributes[TELEMETRY_VERSION])
        }

        @Test
        fun `version is not set when not available`() {
            val evaluationEvent = createTestEvent()

            assertNull(evaluationEvent.attributes[TELEMETRY_VERSION])
        }
    }

    class Variant {
        @Test
        fun `variant is taken from provider evaluation when available`() {
            val variant = "variant"
            val value = "value"
            val evaluationEvent = createTestEvent(
                variant = variant,
                value = value
            )

            assertEquals(variant, evaluationEvent.attributes[TELEMETRY_VARIANT])
            assertNotEquals(value, evaluationEvent.attributes[TELEMETRY_VARIANT])
            assertFalse(evaluationEvent.body.containsKey(TELEMETRY_BODY))
        }

        @Test
        fun `variant is not set when not available`() {
            val evaluationEvent = createTestEvent(variant = null)

            assertNull(evaluationEvent.attributes[TELEMETRY_VARIANT])
        }

        @Test
        fun `telemetry body is set when variant is not available`() {
            val value = "value"
            val evaluationEvent = createTestEvent(
                variant = null,
                value = value
            )

            assertEquals(value, evaluationEvent.body[TELEMETRY_BODY])
        }

        @Test
        fun `telemetry body correctly handles boolean values`() {
            val evaluationEvent = createGenericTestEvent(
                value = true,
                defaultValue = false,
                flagValueType = FlagValueType.BOOLEAN
            )

            assertEquals(true, evaluationEvent.body[TELEMETRY_BODY])
        }

        @Test
        fun `telemetry body correctly handles integer values`() {
            val evaluationEvent = createGenericTestEvent(
                value = 42,
                defaultValue = 0,
                flagValueType = FlagValueType.INTEGER
            )

            assertEquals(42, evaluationEvent.body[TELEMETRY_BODY])
        }
    }

    class Error {
        @Test
        fun `error code and message are taken from provider evaluation when available`() {
            val errorCode = ErrorCode.PARSE_ERROR
            val errorMessage = "error message"
            val evaluationEvent = createTestEvent(
                reason = EvaluationReason.ERROR.name,
                errorCode = errorCode,
                errorMessage = errorMessage
            )

            assertEquals(errorCode, evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertEquals(errorMessage, evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }

        @Test
        fun `error code and message are taken from provider evaluation even if reason is lowercased`() {
            val errorCode = ErrorCode.PARSE_ERROR
            val errorMessage = "error message"
            val evaluationEvent = createTestEvent(
                reason = "error",
                errorCode = errorCode,
                errorMessage = errorMessage
            )

            assertEquals(errorCode, evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertEquals(errorMessage, evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }

        @Test
        fun `error code and message use defaults when reason is error but no details are available`() {
            val evaluationEvent = createTestEvent(
                reason = EvaluationReason.ERROR.name,
                errorCode = null,
                errorMessage = null
            )

            assertEquals(ErrorCode.GENERAL, evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }

        @Test
        fun `error code and message are ignored when reason is not ERROR`() {
            val evaluationEvent = createTestEvent(
                reason = EvaluationReason.UNKNOWN.name,
                errorCode = ErrorCode.PARSE_ERROR,
                errorMessage = "This should be ignored"
            )

            assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }
    }
}