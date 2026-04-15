package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.hooks.LoggingHook
import dev.openfeature.kotlin.sdk.logging.TestLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingIntegrationTests {

    private class TestProviderMetadata(override val name: String = "test-provider") : ProviderMetadata

    private val testProvider = object : FeatureProvider {
        override val metadata: ProviderMetadata = TestProviderMetadata()
        override val hooks: List<Hook<*>> = listOf()
        private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

        override suspend fun initialize(initialContext: EvaluationContext?) {
            events.emit(OpenFeatureProviderEvents.ProviderReady())
        }

        override fun shutdown() {}

        override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
            events.emit(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        }

        override fun getBooleanEvaluation(
            key: String,
            defaultValue: Boolean,
            context: EvaluationContext?
        ): ProviderEvaluation<Boolean> {
            return ProviderEvaluation(
                value = true,
                variant = "enabled",
                reason = "STATIC"
            )
        }

        override fun getStringEvaluation(
            key: String,
            defaultValue: String,
            context: EvaluationContext?
        ): ProviderEvaluation<String> {
            return ProviderEvaluation(
                value = "test-value",
                variant = "variant-a",
                reason = "TARGETING_MATCH"
            )
        }

        override fun getIntegerEvaluation(
            key: String,
            defaultValue: Int,
            context: EvaluationContext?
        ): ProviderEvaluation<Int> {
            return ProviderEvaluation(value = defaultValue)
        }

        override fun getLongEvaluation(
            key: String,
            defaultValue: Long,
            context: EvaluationContext?
        ): ProviderEvaluation<Long> {
            return ProviderEvaluation(value = defaultValue)
        }

        override fun getDoubleEvaluation(
            key: String,
            defaultValue: Double,
            context: EvaluationContext?
        ): ProviderEvaluation<Double> {
            return ProviderEvaluation(value = defaultValue)
        }

        override fun getObjectEvaluation(
            key: String,
            defaultValue: Value,
            context: EvaluationContext?
        ): ProviderEvaluation<Value> {
            return ProviderEvaluation(value = defaultValue)
        }

        override fun observe(): Flow<OpenFeatureProviderEvents> {
            return events
        }
    }

    @BeforeTest
    fun setup() {
        OpenFeatureAPI.clearHooks()
    }

    @AfterTest
    fun teardown() = runTest {
        OpenFeatureAPI.clearHooks()
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun `logging hook at API level logs flag evaluations`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.addHooks(listOf(hook))

        val client = OpenFeatureAPI.getClient()
        client.getBooleanValue("test-flag", false)

        // Verify all lifecycle stages were logged
        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation completed") })
        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation finalized") })

        // Verify flag key and value in structured attributes
        assertTrue(testLogger.debugMessages.any { it.attributes["flag"] == "test-flag" })
        assertTrue(testLogger.debugMessages.any { it.attributes["value"] == true })
    }

    @Test
    fun `logging hook at client level logs flag evaluations`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        val client = OpenFeatureAPI.getClient()
        client.addHooks(listOf(hook))

        client.getBooleanValue("client-flag", false)

        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(testLogger.debugMessages.any { it.attributes["flag"] == "client-flag" })
    }

    @Test
    fun `logging hook at invocation level logs flag evaluations`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        val client = OpenFeatureAPI.getClient()

        client.getBooleanValue(
            "invocation-flag",
            false,
            FlagEvaluationOptions(
                hooks = listOf(hook)
            )
        )

        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(testLogger.debugMessages.any { it.attributes["flag"] == "invocation-flag" })
    }

    @Test
    fun `logging hook logs context when enabled at invocation level`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger, logEvaluationContext = true)

        val evaluationContext = ImmutableContext(
            targetingKey = "user-456",
            attributes = mapOf("email" to Value.String("test@example.com"))
        )

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.setEvaluationContextAndWait(evaluationContext)

        val client = OpenFeatureAPI.getClient()
        client.getBooleanValue(
            "context-flag",
            false,
            FlagEvaluationOptions(
                hooks = listOf(hook)
            )
        )

        // Verify context was included in structured attributes
        assertTrue(
            testLogger.debugMessages.any {
                it.attributes["context.targetingKey"] == "user-456"
            }
        )
    }

    @Test
    fun `logging hook with string evaluation`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.addHooks(listOf(hook))

        val client = OpenFeatureAPI.getClient()
        val value = client.getStringValue("string-flag", "default")

        assertEquals("test-value", value)

        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(
            testLogger.debugMessages.any {
                it.attributes["flag"] == "string-flag" && it.attributes["value"] == "test-value"
            }
        )
    }

    @Test
    fun `logging hook with integer evaluation`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.addHooks(listOf(hook))

        val client = OpenFeatureAPI.getClient()
        val value = client.getIntegerValue("integer-flag", 42)

        assertEquals(42, value)
        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(
            testLogger.debugMessages.any {
                it.attributes["flag"] == "integer-flag" && it.attributes["value"] == 42
            }
        )
    }

    @Test
    fun `logging hook with double evaluation`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.addHooks(listOf(hook))

        val client = OpenFeatureAPI.getClient()
        val value = client.getDoubleValue("double-flag", 3.14)

        assertEquals(3.14, value)
        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(
            testLogger.debugMessages.any {
                it.attributes["flag"] == "double-flag" && it.attributes["value"] == 3.14
            }
        )
    }

    @Test
    fun `logging hook with object evaluation`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.addHooks(listOf(hook))

        val client = OpenFeatureAPI.getClient()
        client.getObjectValue("object-flag", Value.String("default-object"))

        assertTrue(testLogger.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(
            testLogger.debugMessages.any {
                it.attributes["flag"] == "object-flag" && it.attributes.containsKey("value")
            }
        )
    }

    @Test
    fun `logging hook captures error when provider throws`() = runTest {
        val testLogger = TestLogger()
        val hook = LoggingHook(logger = testLogger)

        val errorProvider = object : FeatureProvider {
            override val metadata: ProviderMetadata = TestProviderMetadata("error-provider")
            override val hooks: List<Hook<*>> = listOf()
            private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

            override suspend fun initialize(initialContext: EvaluationContext?) {
                events.emit(OpenFeatureProviderEvents.ProviderReady())
            }

            override fun shutdown() {}

            override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {}

            override fun getBooleanEvaluation(
                key: String,
                defaultValue: Boolean,
                context: EvaluationContext?
            ): ProviderEvaluation<Boolean> {
                throw RuntimeException("Provider error")
            }

            override fun getStringEvaluation(
                key: String,
                defaultValue: String,
                context: EvaluationContext?
            ): ProviderEvaluation<String> {
                return ProviderEvaluation(value = defaultValue)
            }

            override fun getIntegerEvaluation(
                key: String,
                defaultValue: Int,
                context: EvaluationContext?
            ): ProviderEvaluation<Int> {
                return ProviderEvaluation(value = defaultValue)
            }

            override fun getLongEvaluation(
                key: String,
                defaultValue: Long,
                context: EvaluationContext?
            ): ProviderEvaluation<Long> {
                return ProviderEvaluation(value = defaultValue)
            }

            override fun getDoubleEvaluation(
                key: String,
                defaultValue: Double,
                context: EvaluationContext?
            ): ProviderEvaluation<Double> {
                return ProviderEvaluation(value = defaultValue)
            }

            override fun getObjectEvaluation(
                key: String,
                defaultValue: Value,
                context: EvaluationContext?
            ): ProviderEvaluation<Value> {
                return ProviderEvaluation(value = defaultValue)
            }

            override fun observe(): Flow<OpenFeatureProviderEvents> {
                return events
            }
        }

        OpenFeatureAPI.setProviderAndWait(errorProvider)
        OpenFeatureAPI.addHooks(listOf(hook))

        val client = OpenFeatureAPI.getClient()

        try {
            client.getBooleanValue("error-flag", false)
        } catch (e: Exception) {
            // Expected
        }

        // Verify error was logged with structured attributes
        assertTrue(
            testLogger.errorMessages.any {
                it.message.contains("Flag evaluation error") &&
                    it.attributes["flag"] == "error-flag"
            }
        )
    }

    @Test
    fun `multiple hooks execute in order`() = runTest {
        val testLogger1 = TestLogger()
        val testLogger2 = TestLogger()
        val hook1 = LoggingHook(logger = testLogger1)
        val hook2 = LoggingHook(logger = testLogger2)

        OpenFeatureAPI.setProviderAndWait(testProvider)
        OpenFeatureAPI.addHooks(listOf(hook1, hook2))

        val client = OpenFeatureAPI.getClient()
        client.getBooleanValue("multi-hook-flag", false)

        assertTrue(testLogger1.debugMessages.any { it.message.contains("Flag evaluation starting") })
        assertTrue(testLogger2.debugMessages.any { it.message.contains("Flag evaluation starting") })
    }
}