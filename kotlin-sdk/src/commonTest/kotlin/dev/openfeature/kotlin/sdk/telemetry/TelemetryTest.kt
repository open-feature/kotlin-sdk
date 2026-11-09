package dev.openfeature.kotlin.sdk.telemetry

import dev.openfeature.kotlin.sdk.ClientMetadata
import dev.openfeature.kotlin.sdk.EvaluationMetadata
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.FlagValueType
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class TelemetryTest {

    private val providerMetadata = object : ProviderMetadata {
        override val name: String = "TestProvider"
    }

    private val clientMetadata = object : ClientMetadata {
        override val name: String? = "TestClient"
    }

    @Test
    fun `test telemetry event constructs accurately on SUCCESS evaluations`() {
        val hookContext = HookContext(
            flagKey = "my-flag",
            type = FlagValueType.BOOLEAN,
            defaultValue = false,
            ctx = ImmutableContext(targetingKey = "user-123"),
            clientMetadata = clientMetadata,
            providerMetadata = providerMetadata
        )

        val metadataBuilder = EvaluationMetadata.builder()
            .putString(Telemetry.TELEMETRY_FLAG_META_CONTEXT_ID, "override-context")
            .putString(Telemetry.TELEMETRY_FLAG_META_FLAG_SET_ID, "set-1")
            .putString(Telemetry.TELEMETRY_FLAG_META_VERSION, "v1.0")
            .build()

        val details = FlagEvaluationDetails(
            flagKey = "my-flag",
            value = true,
            variant = "on",
            reason = Reason.TARGETING_MATCH.name,
            metadata = metadataBuilder
        )

        val event = Telemetry.createEvaluationEvent(hookContext, details)

        assertEquals(Telemetry.FLAG_EVALUATION_EVENT_NAME, event.name)
        val attrs = event.attributes

        assertEquals("my-flag", attrs[Telemetry.TELEMETRY_KEY])
        assertEquals("TestProvider", attrs[Telemetry.TELEMETRY_PROVIDER])
        assertEquals("targeting_match", attrs[Telemetry.TELEMETRY_REASON])
        assertEquals("on", attrs[Telemetry.TELEMETRY_VARIANT])
        assertEquals(true, attrs[Telemetry.TELEMETRY_VALUE]) // Value is recommended even when variant is present
        assertEquals("override-context", attrs[Telemetry.TELEMETRY_CONTEXT_ID])
        assertEquals("set-1", attrs[Telemetry.TELEMETRY_FLAG_SET_ID])
        assertEquals("v1.0", attrs[Telemetry.TELEMETRY_VERSION])
        assertNull(attrs[Telemetry.TELEMETRY_ERROR_CODE])
        assertNull(attrs[Telemetry.TELEMETRY_ERROR_MSG])
    }

    @Test
    fun `test telemetry event constructs accurately on ERROR evaluations`() {
        val hookContext = HookContext(
            flagKey = "error-flag",
            type = FlagValueType.STRING,
            defaultValue = "fallback",
            ctx = ImmutableContext(),
            clientMetadata = clientMetadata,
            providerMetadata = providerMetadata
        )

        val details = FlagEvaluationDetails(
            flagKey = "error-flag",
            value = "fallback",
            reason = Reason.ERROR.name,
            errorCode = ErrorCode.PROVIDER_FATAL,
            errorMessage = "Provider crashed unexpectedly"
        )

        val event = Telemetry.createEvaluationEvent(hookContext, details)

        val attrs = event.attributes
        assertEquals("error", attrs[Telemetry.TELEMETRY_REASON])
        assertEquals("provider_fatal", attrs[Telemetry.TELEMETRY_ERROR_CODE])
        assertEquals("Provider crashed unexpectedly", attrs[Telemetry.TELEMETRY_ERROR_MSG])
        assertEquals("fallback", attrs[Telemetry.TELEMETRY_VALUE])
    }

    @Test
    fun `test telemetry correctly unwraps Value structures`() {
        val hookContext = HookContext<Value>(
            flagKey = "obj-flag",
            type = FlagValueType.OBJECT,
            defaultValue = Value.String("none"),
            ctx = null,
            clientMetadata = null,
            providerMetadata = providerMetadata
        )

        val structure = Value.Structure(
            mapOf(
                "key1" to Value.String("val1"),
                "key2" to Value.List(listOf(Value.Integer(42)))
            )
        )

        val details = FlagEvaluationDetails<Value>(
            flagKey = "obj-flag",
            value = structure,
            reason = Reason.DEFAULT.name
        )

        val event = Telemetry.createEvaluationEvent(hookContext, details)

        val unwrappedValue = event.attributes[Telemetry.TELEMETRY_VALUE]
        assertNotNull(unwrappedValue)

        @Suppress("UNCHECKED_CAST")
        val map = unwrappedValue as Map<String, Any?>
        assertEquals("val1", map["key1"])

        @Suppress("UNCHECKED_CAST")
        val list = map["key2"] as List<Any?>
        assertEquals(42, list[0])
    }

    @Test
    fun `test telemetry unwraps ValueInstant into ISO 8601 string`() {
        val hookContext = HookContext<Value>(
            flagKey = "instant-flag",
            type = FlagValueType.OBJECT,
            defaultValue = Value.String("none"),
            ctx = null,
            clientMetadata = null,
            providerMetadata = providerMetadata
        )

        val instantStr = "2026-04-20T13:31:20Z"
        val details = FlagEvaluationDetails<Value>(
            flagKey = "instant-flag",
            value = Value.Instant(Instant.parse(instantStr)),
            reason = Reason.DEFAULT.name
        )

        val event = Telemetry.createEvaluationEvent(hookContext, details)

        val unwrappedValue = event.attributes[Telemetry.TELEMETRY_VALUE]
        assertEquals(instantStr, unwrappedValue)
    }

    @Test
    fun `test telemetry ignores null reason gracefully without throwing exceptions`() {
        val hookContext = HookContext<Boolean>(
            flagKey = "null-reason-flag",
            type = FlagValueType.BOOLEAN,
            defaultValue = false,
            ctx = null,
            clientMetadata = null,
            providerMetadata = providerMetadata
        )

        val details = FlagEvaluationDetails(
            flagKey = "null-reason-flag",
            value = true,
            reason = null // Explicitly null to prove String?.equals handles it
        )

        // This proves that evaluationDetails.reason.equals(...) does not throw NPE
        // and correctly bypasses the Error State mapping block.
        val event = Telemetry.createEvaluationEvent(hookContext, details)

        assertNull(event.attributes[Telemetry.TELEMETRY_ERROR_CODE])
        assertNull(event.attributes[Telemetry.TELEMETRY_ERROR_MSG])
        assertEquals(Reason.UNKNOWN.name.lowercase(), event.attributes[Telemetry.TELEMETRY_REASON])
    }

    @Test
    fun `test telemetry falls back to GENERAL error code and omits message when null`() {
        val hookContext = HookContext<Boolean>(
            flagKey = "error-fallback-flag",
            type = FlagValueType.BOOLEAN,
            defaultValue = false,
            ctx = null,
            clientMetadata = null,
            providerMetadata = providerMetadata
        )

        val details = FlagEvaluationDetails(
            flagKey = "error-fallback-flag",
            value = false,
            reason = Reason.ERROR.name,
            errorCode = null, // Trigger fallback
            errorMessage = null // Should be omitted safely
        )

        val event = Telemetry.createEvaluationEvent(hookContext, details)

        val attrs = event.attributes
        assertEquals("error", attrs[Telemetry.TELEMETRY_REASON])
        assertEquals("general", attrs[Telemetry.TELEMETRY_ERROR_CODE])
        assertNull(attrs[Telemetry.TELEMETRY_ERROR_MSG])
        assertEquals(false, attrs[Telemetry.TELEMETRY_VALUE])
    }
}