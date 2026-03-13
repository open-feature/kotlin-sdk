package dev.openfeature.kotlin.sdk.hooks

import dev.openfeature.kotlin.sdk.ClientMetadata
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.FlagValueType
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.logging.TestLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingHookTests {

    private class TestProviderMetadata(override val name: String = "test-provider") : ProviderMetadata
    private class TestClientMetadata(override val name: String = "test-client") : ClientMetadata

    private fun createHookContext(
        flagKey: String = "test-flag",
        defaultValue: Boolean = false,
        evaluationContext: EvaluationContext? = null
    ): HookContext<Boolean> {
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
        val hook = LoggingHook<Boolean>(logger = testLogger)
        val context = createHookContext("my-flag", false)

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("Flag evaluation starting"))
        assertTrue(message.contains("flag='my-flag'"))
        assertTrue(message.contains("type=BOOLEAN"))
        assertTrue(message.contains("defaultValue=false"))
        assertTrue(message.contains("provider='test-provider'"))
        assertTrue(message.contains("client='test-client'"))
    }

    @Test
    fun `after stage logs flag evaluation completed`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails(
            flagKey = "my-flag",
            value = true,
            variant = "on",
            reason = "TARGETING_MATCH"
        )

        hook.after(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("Flag evaluation completed"))
        assertTrue(message.contains("flag='my-flag'"))
        assertTrue(message.contains("value=true"))
        assertTrue(message.contains("variant='on'"))
        assertTrue(message.contains("reason='TARGETING_MATCH'"))
        assertTrue(message.contains("provider='test-provider'"))
    }

    @Test
    fun `error stage logs flag evaluation error`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger)
        val context = createHookContext("my-flag", false)
        val exception = RuntimeException("Connection timeout")

        hook.error(context, exception, emptyMap())

        assertEquals(1, testLogger.errorMessages.size)
        val message = testLogger.errorMessages[0].message
        assertTrue(message.contains("Flag evaluation error"))
        assertTrue(message.contains("flag='my-flag'"))
        assertTrue(message.contains("type=BOOLEAN"))
        assertTrue(message.contains("defaultValue=false"))
        assertTrue(message.contains("provider='test-provider'"))
        assertTrue(message.contains("error='Connection timeout'"))
        assertEquals(exception, testLogger.errorMessages[0].throwable)
    }

    @Test
    fun `finally stage logs flag evaluation finalized`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails(
            flagKey = "my-flag",
            value = false
        )

        hook.finallyAfter(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("Flag evaluation finalized"))
        assertTrue(message.contains("flag='my-flag'"))
    }

    @Test
    fun `finally stage includes error details when present`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger)
        val context = createHookContext("my-flag")
        val details = FlagEvaluationDetails(
            flagKey = "my-flag",
            value = false,
            errorCode = dev.openfeature.kotlin.sdk.exceptions.ErrorCode.PROVIDER_NOT_READY,
            errorMessage = "Provider not initialized"
        )

        hook.finallyAfter(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("Flag evaluation finalized"))
        assertTrue(message.contains("flag='my-flag'"))
        assertTrue(message.contains("errorCode=PROVIDER_NOT_READY"))
        assertTrue(message.contains("errorMessage='Provider not initialized'"))
    }

    @Test
    fun `context logging is disabled by default`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(!message.contains("context="))
        assertTrue(!message.contains("user-123"))
        assertTrue(!message.contains("email"))
    }

    @Test
    fun `context logging works when enabled`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = emptySet() // Bypass default filtering for this test
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"), "plan" to Value.String("premium"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("context="))
        assertTrue(message.contains("targetingKey='user-123'"))
        assertTrue(message.contains("attributes="))
        assertTrue(message.contains("email=user@example.com"))
        assertTrue(message.contains("plan=premium"))
    }

    @Test
    fun `hint override enables context logging`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger, logEvaluationContext = false)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val hints = mapOf("logEvaluationContext" to true)

        hook.before(context, hints)

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("context="))
        assertTrue(message.contains("targetingKey='user-123'"))
    }

    @Test
    fun `hint override disables context logging`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("email" to Value.String("user@example.com"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val hints = mapOf("logEvaluationContext" to false)

        hook.before(context, hints)

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(!message.contains("context="))
        assertTrue(!message.contains("user-123"))
    }

    @Test
    fun `context logging works in after stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123"
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails(
            flagKey = "my-flag",
            value = true
        )

        hook.after(context, details, emptyMap())

        assertEquals(1, testLogger.debugMessages.size)
        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("context="))
        assertTrue(message.contains("targetingKey='user-123'"))
    }

    @Test
    fun `context logging works in error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(logger = testLogger, logEvaluationContext = true)
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123"
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val exception = RuntimeException("Test error")

        hook.error(context, exception, emptyMap())

        assertEquals(1, testLogger.errorMessages.size)
        val message = testLogger.errorMessages[0].message
        assertTrue(message.contains("context="))
        assertTrue(message.contains("targetingKey='user-123'"))
    }

    @Test
    fun `includeAttributes filters to only specified attributes`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
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

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("region=us-east"))
        assertTrue(message.contains("plan=premium"))
        assertTrue(!message.contains("email"))
        assertTrue(!message.contains("phone"))
    }

    @Test
    fun `excludeAttributes filters out specified attributes`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = setOf("email", "phone")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("us-east"),
                "phone" to Value.String("555-1234")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("region=us-east"))
        assertTrue(!message.contains("email"))
        assertTrue(!message.contains("phone"))
    }

    @Test
    fun `DEFAULT_SENSITIVE_KEYS are filtered by default`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("us-east"),
                "ssn" to Value.String("123-45-6789"),
                "password" to Value.String("secret123")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("region=us-east"))
        assertTrue(!message.contains("email"))
        assertTrue(!message.contains("ssn"))
        assertTrue(!message.contains("password"))
    }

    @Test
    fun `includeAttributes takes precedence over excludeAttributes`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            includeAttributes = setOf("email"),
            excludeAttributes = setOf("email")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("us-east")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("email=user@example.com"))
        assertTrue(!message.contains("region"))
    }

    @Test
    fun `empty excludeAttributes bypasses default filtering`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = emptySet()
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("us-east")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("email=user@example.com"))
        assertTrue(message.contains("region=us-east"))
    }

    @Test
    fun `filtering works in after stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            includeAttributes = setOf("region")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-456",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("us-west")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails(flagKey = "my-flag", value = true)

        hook.after(context, details, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("region=us-west"))
        assertTrue(!message.contains("email"))
    }

    @Test
    fun `filtering works in error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            excludeAttributes = setOf("email")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-789",
            attributes = mapOf(
                "email" to Value.String("user@example.com"),
                "region" to Value.String("eu-central")
            )
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val exception = RuntimeException("Test error")

        hook.error(context, exception, emptyMap())

        val message = testLogger.errorMessages[0].message
        assertTrue(message.contains("region=eu-central"))
        assertTrue(!message.contains("email"))
    }

    @Test
    fun `targeting key is logged by default`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            includeAttributes = setOf("region")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user-123",
            attributes = mapOf("region" to Value.String("us-east"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(message.contains("targetingKey='user-123'"))
        assertTrue(message.contains("region=us-east"))
    }

    @Test
    fun `targeting key can be excluded with logTargetingKey false`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            logTargetingKey = false,
            includeAttributes = setOf("region")
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "user@example.com",
            attributes = mapOf("region" to Value.String("us-east"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)

        hook.before(context, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(!message.contains("targetingKey"))
        assertTrue(!message.contains("user@example.com"))
        assertTrue(message.contains("region=us-east"))
    }

    @Test
    fun `logTargetingKey false works in after stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            logTargetingKey = false
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "sensitive-user-id",
            attributes = mapOf("region" to Value.String("eu-west"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val details = FlagEvaluationDetails(flagKey = "my-flag", value = true)

        hook.after(context, details, emptyMap())

        val message = testLogger.debugMessages[0].message
        assertTrue(!message.contains("targetingKey"))
        assertTrue(!message.contains("sensitive-user-id"))
        assertTrue(message.contains("context="))
    }

    @Test
    fun `logTargetingKey false works in error stage`() {
        val testLogger = TestLogger()
        val hook = LoggingHook<Boolean>(
            logger = testLogger,
            logEvaluationContext = true,
            logTargetingKey = false
        )
        val evaluationContext = ImmutableContext(
            targetingKey = "sensitive-user-id",
            attributes = mapOf("region" to Value.String("ap-south"))
        )
        val context = createHookContext("my-flag", false, evaluationContext)
        val exception = RuntimeException("Test error")

        hook.error(context, exception, emptyMap())

        val message = testLogger.errorMessages[0].message
        assertTrue(!message.contains("targetingKey"))
        assertTrue(!message.contains("sensitive-user-id"))
        assertTrue(message.contains("context="))
    }
}
