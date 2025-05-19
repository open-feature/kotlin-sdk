package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import dev.openfeature.sdk.Reason as EvaluationReason

private val providerMetadata = object : ProviderMetadata {
    override val name: String
        get() = "Provider name"
}

class TelemetryTest {
    @Test
    fun `flagKey is set correctly`() {
        val flagKey = "flag key"
        val ctx = ImmutableContext("targeting key")
        val hookContext = HookContext(
            flagKey,
            FlagValueType.STRING,
            "default",
            ctx,
            null,
            providerMetadata
        )
        val flagEvaluationDetails = FlagEvaluationDetails.from(ProviderEvaluation("value"), flagKey)
        val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

        assertEquals(flagKey, evaluationEvent.attributes[TELEMETRY_KEY])
    }

    class ProviderName {
        @Test
        fun `provider name is set correctly`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(ProviderEvaluation("value"), flagKey)
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(providerMetadata.name, evaluationEvent.attributes[TELEMETRY_PROVIDER])
        }

        @Test
        fun `provider name is set to empty string when not available`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                object : ProviderMetadata {
                    override val name: String?
                        get() = null
                }
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(ProviderEvaluation("value"), flagKey)
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals("", evaluationEvent.attributes[TELEMETRY_PROVIDER])
        }
    }

    class Reason {
        @Test
        fun `create EvaluationEvent with UNKNOWN reason if reason is null`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    reason = null
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(EvaluationReason.UNKNOWN.name.lowercase(), evaluationEvent.attributes[TELEMETRY_REASON])
        }

        @Test
        fun `create EvaluationEvent with correct reason if reason is set`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    reason = EvaluationReason.TARGETING_MATCH.name.lowercase()
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(
                EvaluationReason.TARGETING_MATCH.name.lowercase(),
                evaluationEvent.attributes[TELEMETRY_REASON]
            )
        }
    }

    class ContextId {
        @Test
        fun `contextId is taken from evaluation metadata when available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val contextId = "contextId metadata"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("contextId", contextId)))
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    metadata = evaluationMetadata
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(contextId, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
            assertNotEquals(targetingKey, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
        }

        @Test
        fun `contextId is taken from ctx when evaluation metadata is not available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(ProviderEvaluation("value"), flagKey)
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(targetingKey, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
        }
    }

    class FlagSetId {
        @Test
        fun `flagSetId is set correctly when available`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagSetId = "flag set id"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("flagSetId", flagSetId)))
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    metadata = evaluationMetadata
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(flagSetId, evaluationEvent.attributes[TELEMETRY_FLAG_SET_ID])
        }

        @Test
        fun `flagSetId is not set when not available`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(ProviderEvaluation("value"), flagKey)
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertNull(evaluationEvent.attributes[TELEMETRY_FLAG_SET_ID])
        }
    }

    class Version {
        @Test
        fun `version is set correctly when available`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val version = "flag set id"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("version", version)))
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    metadata = evaluationMetadata
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(version, evaluationEvent.attributes[TELEMETRY_VERSION])
        }

        @Test
        fun `version is not set when not available`() {
            val flagKey = "flag key"
            val ctx = ImmutableContext("targeting key")
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(ProviderEvaluation("value"), flagKey)
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertNull(evaluationEvent.attributes[TELEMETRY_VERSION])
        }
    }

    class Variant {
        @Test
        fun `variant is taken from provider evaluation when available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val variant = "variant"
            val value = "value"
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    value,
                    variant
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(variant, evaluationEvent.attributes[TELEMETRY_VARIANT])
            assertNotEquals(value, evaluationEvent.attributes[TELEMETRY_VARIANT])
        }

        @Test
        fun `variant is not set when not available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val variant = null
            val value = "value"
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    value,
                    variant
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertNull(evaluationEvent.attributes[TELEMETRY_VARIANT])
        }

        @Test
        fun `telemetry body is set when variant is not available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val variant = null
            val value = "value"
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    value,
                    variant
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(value, evaluationEvent.body[TELEMETRY_BODY])
        }
    }

    class Error {
        @Test
        fun `error code and message are taken from provider evaluation when available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val errorCode = ErrorCode.PARSE_ERROR
            val errorMessage = "error message"
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    reason = EvaluationReason.ERROR.name,
                    errorCode = errorCode,
                    errorMessage = errorMessage
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(errorCode, evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertEquals(errorMessage, evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }

        @Test
        fun `error code and message use defaults when reason is error but no details are available`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    reason = EvaluationReason.ERROR.name,
                    errorCode = null,
                    errorMessage = null
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertEquals(ErrorCode.GENERAL, evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }

        @Test
        fun `error code and message are not set when no error is present`() {
            val flagKey = "flag key"
            val targetingKey = "targeting key"
            val ctx = ImmutableContext(targetingKey)
            val hookContext = HookContext(
                flagKey,
                FlagValueType.STRING,
                "default",
                ctx,
                null,
                providerMetadata
            )
            val flagEvaluationDetails = FlagEvaluationDetails.from(
                ProviderEvaluation(
                    "value",
                    "variant",
                    reason = EvaluationReason.UNKNOWN.name,
                    errorCode = null,
                    errorMessage = null
                ),
                flagKey
            )
            val evaluationEvent = createEvaluationEvent(hookContext, flagEvaluationDetails)

            assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }
    }
}