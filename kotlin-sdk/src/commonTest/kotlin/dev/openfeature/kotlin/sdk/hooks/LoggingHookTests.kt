package dev.openfeature.kotlin.sdk.hooks

import dev.openfeature.kotlin.sdk.ClientMetadata
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.FlagValueType
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.logging.LogLevel
import dev.openfeature.kotlin.sdk.logging.TestLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingHookTests {

    private class TestProviderMetadata(override val name: String = "test-provider") : ProviderMetadata
    private class TestClientMetadata(override val name: String = "test-client") : ClientMetadata

    private fun createHookContext(
        flagKey: String = "test-flag",
        defaultValue: Any = false,
        evaluationContext: EvaluationContext? = null
    ): HookContext<Any> {
        return HookContext(
            flagKey = flagKey,
            type = FlagValueType.BOOLEAN,
            defaultValue = defaultValue,
            ctx = evaluationContext,
            clientMetadata = TestClientMetadata(),
            providerMetadata = TestProviderMetadata()
        )
    }

    @Test
    fun `before stage logs flag evaluation starting`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag", false)

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertTrue(entry.message.contains("Flag evaluation starting"))
        assertEquals("my-flag", entry.attributes["flag"])
        assertEquals("BOOLEAN", entry.attributes["type"])
        assertEquals(false, entry.attributes["defaultValue"])
        assertEquals("test-provider", entry.attributes["provider"])
        assertEquals("test-client", entry.attributes["client"])
    }

    @Test
    fun `after stage logs flag evaluation completed`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails<Any>(
            flagKey = "my-flag",
            value = true,
            variant = "on",
            reason = "TARGETING_MATCH"
        )

        hook.after(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertTrue(entry.message.contains("Flag evaluation completed"))
        assertEquals("my-flag", entry.attributes["flag"])
        assertEquals(true, entry.attributes["value"])
        assertEquals("on", entry.attributes["variant"])
        assertEquals("TARGETING_MATCH", entry.attributes["reason"])
        assertEquals("test-provider", entry.attributes["provider"])
    }

    @Test
    fun `error stage logs flag evaluation error`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag", false)
        val exception = RuntimeException("Connection timeout")

        hook.error(context, exception, emptyMap())

        assertEquals(1, testLogger.errorMessages.size)
        val entry = testLogger.errorMessages[0]
        assertTrue(entry.message.contains("Flag evaluation error"))
        assertEquals("my-flag", entry.attributes["flag"])
        assertEquals("BOOLEAN", entry.attributes["type"])
        assertEquals(false, entry.attributes["defaultValue"])
        assertEquals("test-provider", entry.attributes["provider"])
        assertEquals("Connection timeout", entry.attributes["error"])
        assertEquals(exception, entry.throwable)
    }

    @Test
    fun `finally stage logs flag evaluation finalized`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails<Any>(
            flagKey = "my-flag",
            value = false
        )

        hook.finallyAfter(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertTrue(entry.message.contains("Flag evaluation finalized"))
        assertEquals("my-flag", entry.attributes["flag"])
        assertFalse(entry.attributes.containsKey("errorCode"))
        assertFalse(entry.attributes.containsKey("errorMessage"))
    }

    @Test
    fun `finally stage includes error details when present`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails<Any>(
            flagKey = "my-flag",
            value = false,
            errorCode = dev.openfeature.kotlin.sdk.exceptions.ErrorCode.PROVIDER_NOT_READY,
            errorMessage = "Provider not initialized"
        )

        hook.finallyAfter(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertTrue(entry.message.contains("Flag evaluation finalized"))
        assertEquals("my-flag", entry.attributes["flag"])
        assertEquals("PROVIDER_NOT_READY", entry.attributes["errorCode"])
        assertEquals("Provider not initialized", entry.attributes["errorMessage"])
    }

    @Test
    fun `context logging is disabled by default`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.targetingKey"))
        assertFalse(entry.attributes.containsKey("context.email"))
    }

    @Test
    fun `context logging works when enabled`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = true, excludeAttributes = emptySet())
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"), "plan" to Value.String("premium"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertEquals("user-123", entry.attributes["context.targetingKey"])
        assertEquals("user@example.com", entry.attributes["context.email"])
        assertEquals("premium", entry.attributes["context.plan"])
    }

    @Test
    fun `hint override enables context logging`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = false)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val hints = mapOf("logEvaluationContext" to true)

        hook.before(context, hints)

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertEquals("user-123", entry.attributes["context.targetingKey"])
    }

    @Test
    fun `hint override disables context logging`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val hints = mapOf("logEvaluationContext" to false)

        hook.before(context, hints)

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.targetingKey"))
        assertFalse(entry.attributes.containsKey("context.email"))
    }

    @Test
    fun `context logging works in after stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123"
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails<Any>(
            flagKey = "my-flag",
            value = true
        )

        hook.after(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertEquals("user-123", entry.attributes["context.targetingKey"])
    }

    @Test
    fun `context logging works in error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123"
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val exception = RuntimeException("Test error")

        hook.error(context, exception, emptyMap())

        assertEquals(1, testLogger.errorMessages.size)
        val entry = testLogger.errorMessages[0]
        assertEquals("user-123", entry.attributes["context.targetingKey"])
    }

    @Test
    fun `finallyAfter does not include context even when logEvaluationContext is true`() {
        // finallyAfter intentionally omits context — it only logs completion status
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("region" to Value.String("us-east"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails<Any>(flagKey = "my-flag", value = false)

        hook.finallyAfter(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.targetingKey"))
        assertFalse(entry.attributes.containsKey("context.region"))
    }

    @Test
    fun `configurable log levels route messages to correct level`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            beforeLogLevel = LogLevel.INFO,
            afterLogLevel = LogLevel.INFO,
            errorLogLevel = LogLevel.WARN,
            finallyLogLevel = LogLevel.INFO
        )
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails<Any>(flagKey = "my-flag", value = true)
        val exception = RuntimeException("err")

        hook.before(context, emptyMap())
        hook.after(context, details, emptyMap())
        hook.error(context, exception, emptyMap())
        hook.finallyAfter(context, details, emptyMap())

        assertEquals(0, testLogger.debugMessages.size)
        assertEquals(3, testLogger.infoMessages.size)
        assertEquals(1, testLogger.warnMessages.size)
        assertEquals(0, testLogger.errorMessages.size)
    }

    @Test
    fun `default log levels use debug for before after finally and error for error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails<Any>(flagKey = "my-flag", value = true)
        val exception = RuntimeException("err")

        hook.before(context, emptyMap())
        hook.after(context, details, emptyMap())
        hook.error(context, exception, emptyMap())
        hook.finallyAfter(context, details, emptyMap())

        assertEquals(3, testLogger.debugMessages.size)
        assertEquals(1, testLogger.errorMessages.size)
    }

    @Test
    fun `after stage omits null variant and reason`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails<Any>(
            flagKey = "my-flag",
            value = true
        )

        hook.after(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("variant"))
        assertFalse(entry.attributes.containsKey("reason"))
    }

    @Test
    fun `before stage omits client when clientMetadata is null`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)
        val context = HookContext<Any>(
            flagKey = "my-flag",
            type = FlagValueType.BOOLEAN,
            defaultValue = false,
            ctx = null,
            clientMetadata = null,
            providerMetadata = TestProviderMetadata()
        )

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("client"))
    }

    @Test
    fun `includeAttributes filters to only specified attributes`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            includeAttributes = setOf("region", "plan")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("us-east"),
                "plan" to Value.String("premium"),
                "phone" to Value.String("555-1234")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertEquals("us-east", entry.attributes["context.region"])
        assertEquals("premium", entry.attributes["context.plan"])
        assertFalse(entry.attributes.containsKey("context.email"))
        assertFalse(entry.attributes.containsKey("context.phone"))
    }

    @Test
    fun `excludeAttributes filters out specified attributes`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = setOf("ssn", "creditCard")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "ssn" to Value.String("123-45-6789"),
                "creditCard" to Value.String("4111-1111-1111-1111"),
                "region" to Value.String("us-east")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.ssn"))
        assertFalse(entry.attributes.containsKey("context.creditCard"))
        assertEquals("us-east", entry.attributes["context.region"])
    }

    @Test
    fun `DEFAULT_SENSITIVE_KEYS are filtered by default`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "phone" to Value.String("555-1234"),
                "region" to Value.String("us-east")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.email"))
        assertFalse(entry.attributes.containsKey("context.phone"))
        assertEquals("us-east", entry.attributes["context.region"])
    }

    @Test
    fun `includeAttributes takes precedence over excludeAttributes`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            includeAttributes = setOf("email"),
            excludeAttributes = setOf("email")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertEquals("user@example.com", entry.attributes["context.email"])
    }

    @Test
    fun `empty excludeAttributes bypasses default filtering`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = emptySet()
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "plan" to Value.String("premium")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertEquals("user@example.com", entry.attributes["context.email"])
        assertEquals("premium", entry.attributes["context.plan"])
    }

    @Test
    fun `filtering works in after stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = setOf("ssn")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "ssn" to Value.String("123-45-6789"),
                "region" to Value.String("us-east")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails<Any>(flagKey = "my-flag", value = true)

        hook.after(context, details, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.ssn"))
        assertEquals("us-east", entry.attributes["context.region"])
    }

    @Test
    fun `filtering works in error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = setOf("ssn")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "ssn" to Value.String("123-45-6789"),
                "region" to Value.String("us-east")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.error(context, RuntimeException("err"), emptyMap())

        val entry = testLogger.errorMessages[0]
        assertFalse(entry.attributes.containsKey("context.ssn"))
        assertEquals("us-east", entry.attributes["context.region"])
    }

    @Test
    fun `targeting key is logged by default`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true
        )
        val evaluationContext = ImmutableContext(targetingKey = "user-123")
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertEquals("user-123", entry.attributes["context.targetingKey"])
    }

    @Test
    fun `targeting key can be excluded with logTargetingKey false`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            logTargetingKey = false
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("region" to Value.String("us-east"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.targetingKey"))
        assertEquals("us-east", entry.attributes["context.region"])
    }

    @Test
    fun `logTargetingKey false works in after stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            logTargetingKey = false
        )
        val evaluationContext = ImmutableContext(targetingKey = "user-123")
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails<Any>(flagKey = "my-flag", value = true)

        hook.after(context, details, emptyMap())

        val entry = testLogger.debugMessages[0]
        assertFalse(entry.attributes.containsKey("context.targetingKey"))
    }

    @Test
    fun `logTargetingKey false works in error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook(
            logger = testLogger,
            logEvaluationContext = true,
            logTargetingKey = false
        )
        val evaluationContext = ImmutableContext(targetingKey = "user-123")
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.error(context, RuntimeException("err"), emptyMap())

        val entry = testLogger.errorMessages[0]
        assertFalse(entry.attributes.containsKey("context.targetingKey"))
    }
}