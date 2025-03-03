package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.ErrorCode
import org.junit.Assert
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import dev.openfeature.sdk.Reason as EvaluationReason

private val providerMetadata = object : ProviderMetadata {
    override val name: String
        get() = "Provider name"
}

@RunWith(Enclosed::class)
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
            mock(),
            providerMetadata
        )
        val providerEvaluation = ProviderEvaluation("value")
        val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

        Assert.assertEquals(flagKey, evaluationEvent.attributes[TELEMETRY_KEY])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation("value")
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(providerMetadata.name, evaluationEvent.attributes[TELEMETRY_PROVIDER])
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
                mock(),
                object : ProviderMetadata {
                    override val name: String?
                        get() = null
                }
            )
            val providerEvaluation = ProviderEvaluation("value")
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals("", evaluationEvent.attributes[TELEMETRY_PROVIDER])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation(
                "value",
                reason = null
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(EvaluationReason.UNKNOWN.name.lowercase(), evaluationEvent.attributes[TELEMETRY_REASON])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation(
                "value",
                reason = EvaluationReason.TARGETING_MATCH.name.lowercase()
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(EvaluationReason.TARGETING_MATCH.name.lowercase(), evaluationEvent.attributes[TELEMETRY_REASON])
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
                mock(),
                providerMetadata
            )
            val contextId = "contextId metadata"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("contextId", contextId)))
            val providerEvaluation = ProviderEvaluation(
                "value",
                metadata = evaluationMetadata
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(contextId, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
            Assert.assertNotEquals(targetingKey, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation("value")
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(targetingKey, evaluationEvent.attributes[TELEMETRY_CONTEXT_ID])
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
                mock(),
                providerMetadata
            )
            val flagSetId = "flag set id"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("flagSetId", flagSetId)))
            val providerEvaluation = ProviderEvaluation(
                "value",
                metadata = evaluationMetadata
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(flagSetId, evaluationEvent.attributes[TELEMETRY_FLAG_SET_ID])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation("value")
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertNull(evaluationEvent.attributes[TELEMETRY_FLAG_SET_ID])
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
                mock(),
                providerMetadata
            )
            val version = "flag set id"
            val evaluationMetadata = EvaluationMetadata(mapOf(Pair("version", version)))
            val providerEvaluation = ProviderEvaluation(
                "value",
                metadata = evaluationMetadata
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(version, evaluationEvent.attributes[TELEMETRY_VERSION])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation("value")
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertNull(evaluationEvent.attributes[TELEMETRY_VERSION])
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
                mock(),
                providerMetadata
            )
            val variant = "variant"
            val value = "value"
            val providerEvaluation = ProviderEvaluation(
                value,
                variant
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(variant, evaluationEvent.attributes[TELEMETRY_VARIANT])
            Assert.assertNotEquals(value, evaluationEvent.attributes[TELEMETRY_VARIANT])
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
                mock(),
                providerMetadata
            )
            val variant = null
            val value = "value"
            val providerEvaluation = ProviderEvaluation(
                value,
                variant
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertNull(evaluationEvent.attributes[TELEMETRY_VARIANT])
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
                mock(),
                providerMetadata
            )
            val variant = null
            val value = "value"
            val providerEvaluation = ProviderEvaluation(
                value,
                variant
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(value, evaluationEvent.body[TELEMETRY_BODY])
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
                mock(),
                providerMetadata
            )
            val errorCode = ErrorCode.PARSE_ERROR
            val errorMessage = "error message"
            val providerEvaluation = ProviderEvaluation(
                "value",
                errorCode = errorCode,
                errorMessage = errorMessage
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertEquals(errorCode, evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            Assert.assertEquals(errorMessage, evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
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
                mock(),
                providerMetadata
            )
            val providerEvaluation = ProviderEvaluation(
                "value",
                "variant",
                errorCode = null,
                errorMessage = null
            )
            val evaluationEvent = createEvaluationEvent(hookContext, providerEvaluation)

            Assert.assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_CODE])
            Assert.assertNull(evaluationEvent.attributes[TELEMETRY_ERROR_MSG])
        }
    }
}