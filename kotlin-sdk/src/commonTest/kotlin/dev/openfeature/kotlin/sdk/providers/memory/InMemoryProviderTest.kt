package dev.openfeature.kotlin.sdk.providers.memory

import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.FlagNotFoundError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.ProviderNotReadyError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.TypeMismatchError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryProviderTest {

    private lateinit var provider: InMemoryProvider

    @BeforeTest
    fun setup() = runTest {
        val flags = mapOf(
            "boolean-flag" to Flag.builder<Boolean>()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("on")
                .build(),
            "string-flag" to Flag.builder<String>()
                .variant("greeting", "hi")
                .defaultVariant("greeting")
                .build(),
            "integer-flag" to Flag.builder<Int>()
                .variant("ten", 10)
                .defaultVariant("ten")
                .build(),
            "object-flag" to Flag.builder<Value>()
                .variant(
                    "template",
                    Value.Structure(
                        mapOf(
                            "showImages" to Value.Boolean(true),
                            "title" to Value.String("Check out these pics!"),
                            "imagesPerPage" to Value.Integer(100)
                        )
                    )
                )
                .defaultVariant("template")
                .build()
        )
        provider = InMemoryProvider(flags)
        OpenFeatureAPI.setProviderAndWait(provider)
    }

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun `getBooleanEvaluation returns correctly`() = runTest {
        val client = OpenFeatureAPI.getClient()
        assertEquals(true, client.getBooleanValue("boolean-flag", false))
    }

    @Test
    fun `getStringEvaluation returns correctly`() = runTest {
        val client = OpenFeatureAPI.getClient()
        assertEquals("hi", client.getStringValue("string-flag", "dummy"))
    }

    @Test
    fun `getIntegerEvaluation returns correctly`() = runTest {
        val client = OpenFeatureAPI.getClient()
        assertEquals(10, client.getIntegerValue("integer-flag", 999))
    }

    @Test
    fun `getObjectEvaluation returns correctly`() = runTest {
        val client = OpenFeatureAPI.getClient()
        val defaultObj = Value.Structure(mapOf())
        val obj = client.getObjectValue("object-flag", defaultObj) as Value.Structure
        assertEquals(true, (obj.asStructure()!!["showImages"] as Value.Boolean).boolean)
        assertEquals("Check out these pics!", (obj.asStructure()!!["title"] as Value.String).string)
    }

    @Test
    fun `should throw FlagNotFoundError when flag is missing`() = runTest {
        assertFailsWith<FlagNotFoundError> {
            provider.getBooleanEvaluation("not-found-flag", false, null)
        }
    }

    @Test
    fun `should throw TypeMismatchError on wrong type cast`() = runTest {
        assertFailsWith<TypeMismatchError> {
            provider.getBooleanEvaluation("string-flag", false, null)
        }
    }

    @Test
    fun `should throw ProviderNotReadyError if not initialized`() = runTest {
        val newProvider = InMemoryProvider()
        assertFailsWith<ProviderNotReadyError> {
            newProvider.getBooleanEvaluation("some_flag", false, null)
        }
    }

    @Test
    fun `disabled flag returns default value and reason DISABLED`() = runTest {
        val disabledFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant("on")
            .disabled(true)
            .build()

        val localProvider = InMemoryProvider(mapOf("disabled-flag" to disabledFlag))
        localProvider.initialize(null)

        val eval = localProvider.getBooleanEvaluation("disabled-flag", false, null)
        assertEquals(false, eval.value)
        assertEquals(Reason.DISABLED.toString(), eval.reason)
    }

    @Test
    fun `contextEvaluator returning null falls back to defaultVariant with Reason DEFAULT`() = runTest {
        val evaluatorFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant("on")
            .contextEvaluator { _, _ -> null }
            .build()

        val localProvider = InMemoryProvider(mapOf("evaluator-flag" to evaluatorFlag))
        localProvider.initialize(null)

        val eval = localProvider.getBooleanEvaluation("evaluator-flag", false, null)
        assertEquals(true, eval.value) // defaultVariant "on" has value true
        assertEquals(Reason.DEFAULT.toString(), eval.reason)
    }

    @Test
    fun `updateFlags fires ProviderConfigurationChanged event`() = runTest {
        val newFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .defaultVariant("on")
            .build()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            val event = provider.observe()
                .first { it is OpenFeatureProviderEvents.ProviderConfigurationChanged }
                as OpenFeatureProviderEvents.ProviderConfigurationChanged
            assertEquals(setOf("new-flag"), event.eventDetails?.flagsChanged)
        }

        provider.updateFlags(mapOf("new-flag" to newFlag))
        job.join()

        val multiFlags = mapOf(
            "another-flag" to Flag.builder<Boolean>().variant("on", true).defaultVariant("on").build()
        )
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            val event = provider.observe()
                .first { it is OpenFeatureProviderEvents.ProviderConfigurationChanged }
                as OpenFeatureProviderEvents.ProviderConfigurationChanged
            assertEquals(setOf("another-flag"), event.eventDetails?.flagsChanged)
        }
        provider.updateFlags(multiFlags)
        job2.join()
    }

    @Test
    fun `context evaluator exception defaults to fallback and Reason ERROR`() = runTest {
        val evaluatorFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant("on")
            .contextEvaluator { _, _ -> throw Exception("Simulated evaluation error") }
            .build()

        val localProvider = InMemoryProvider(mapOf("evaluator-error-flag" to evaluatorFlag))
        localProvider.initialize(null)

        val eval = localProvider.getBooleanEvaluation("evaluator-error-flag", false, null)
        assertEquals(true, eval.value) // defaultVariant "on" has value true
        assertEquals(Reason.ERROR.toString(), eval.reason)
        assertEquals(dev.openfeature.kotlin.sdk.exceptions.ErrorCode.GENERAL, eval.errorCode)
        assertEquals("Simulated evaluation error", eval.errorMessage)
    }

    @Test
    fun `variant resolution is correct for STATIC TARGETING_MATCH DEFAULT and ERROR reasons`() = runTest {
        val staticFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .defaultVariant("on")
            .build()

        val targetingFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .variant("off", false)
            .defaultVariant("on")
            .contextEvaluator { _, _ -> "off" }
            .build()

        val defaultFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .defaultVariant("on")
            .contextEvaluator { _, _ -> null }
            .build()

        val errorFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .defaultVariant("on")
            .contextEvaluator { _, _ -> throw Exception("Simulated error") }
            .build()

        val flags = mapOf(
            "static-flag" to staticFlag,
            "targeting-flag" to targetingFlag,
            "default-flag" to defaultFlag,
            "error-flag" to errorFlag
        )

        val localProvider = InMemoryProvider(flags)
        localProvider.initialize(null)

        val staticEval = localProvider.getBooleanEvaluation("static-flag", false, null)
        assertEquals(Reason.STATIC.toString(), staticEval.reason)
        assertEquals("on", staticEval.variant)

        val targetingEval = localProvider.getBooleanEvaluation("targeting-flag", false, null)
        assertEquals(Reason.TARGETING_MATCH.toString(), targetingEval.reason)
        assertEquals("off", targetingEval.variant)

        val defaultEval = localProvider.getBooleanEvaluation("default-flag", false, null)
        assertEquals(Reason.DEFAULT.toString(), defaultEval.reason)
        assertEquals("on", defaultEval.variant)

        val errorEval = localProvider.getBooleanEvaluation("error-flag", false, null)
        assertEquals(Reason.ERROR.toString(), errorEval.reason)
        assertEquals("on", errorEval.variant)
    }

    @Test
    fun `updateFlags actually mutates the flag dictionary`() = runTest {
        val newFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .defaultVariant("on")
            .build()

        provider.updateFlags(mapOf("my-new-flag" to newFlag))
        val eval1 = provider.getBooleanEvaluation("my-new-flag", false, null)
        assertEquals(true, eval1.value)

        val multiFlags = mapOf(
            "my-multi-flag" to Flag.builder<Boolean>().variant("off", false).defaultVariant("off").build()
        )
        provider.updateFlags(multiFlags)
        val eval2 = provider.getBooleanEvaluation("my-multi-flag", true, null)
        assertEquals(false, eval2.value)
    }

    @Test
    fun `missing defaultVariant throws TypeMismatchError`() = runTest {
        val nullDefaultFlag = Flag<Boolean>(
            variants = mapOf("on" to true),
            defaultVariant = null
        )
        val localProvider = InMemoryProvider(mapOf("no-default" to nullDefaultFlag))
        localProvider.initialize(null)

        val exception = assertFailsWith<TypeMismatchError> {
            localProvider.getBooleanEvaluation("no-default", false, null)
        }
        assertEquals("flag no-default value could not be resolved or cast", exception.message)
    }

    @Test
    fun `shutdown resets state to NotReady`() = runTest {
        val localProvider = InMemoryProvider()
        localProvider.initialize(null)

        assertFailsWith<FlagNotFoundError> {
            localProvider.getBooleanEvaluation("missing", false, null)
        }

        localProvider.shutdown()

        assertFailsWith<ProviderNotReadyError> {
            localProvider.getBooleanEvaluation("missing", false, null)
        }
    }

    /* ktlint-disable max-line-length */
    @Test
    fun `no evaluation exception and Flag validation throws IllegalArgumentException if defaultVariant missing from variants`() = runTest {
        val exception = assertFailsWith<IllegalArgumentException> {
            Flag<Boolean>(
                variants = mapOf("on" to true),
                defaultVariant = "off"
            )
        }
        assertEquals("defaultVariant (off) is not present in variants map", exception.message)

        // Assert no evaluation exception happens if it is valid
        val validFlag = Flag<Boolean>(
            variants = mapOf("on" to true),
            defaultVariant = "on"
        )
        val provider = InMemoryProvider(mapOf("valid" to validFlag))
        provider.initialize(null)
        val eval = provider.getBooleanEvaluation("valid", false, null)
        assertEquals(true, eval.value)
    }
    /* ktlint-enable max-line-length */

    /* ktlint-disable max-line-length */
    @Test
    fun `evaluation exception without defaultVariant falls back to parameter defaultValue with Reason ERROR`() = runTest {
        val flagWithoutDefaultVariant = Flag<Boolean>(
            variants = mapOf("on" to true),
            defaultVariant = null,
            contextEvaluator = { _, _ -> throw Exception("Simulated error with no default variant") }
        )

        val localProvider = InMemoryProvider(mapOf("no-default-error" to flagWithoutDefaultVariant))
        localProvider.initialize(null)

        val eval = localProvider.getBooleanEvaluation("no-default-error", false, null)
        assertEquals(false, eval.value) // Should fallback to `defaultValue` = false
        assertEquals(Reason.ERROR.toString(), eval.reason)
        assertEquals(ErrorCode.GENERAL, eval.errorCode)
        assertEquals("Simulated error with no default variant", eval.errorMessage)
    }

    @Test
    fun `contextEvaluator returning unknown variant falls back to defaultVariant with Reason ERROR`() = runTest {
        val unknownVariantFlag = Flag.builder<Boolean>()
            .variant("on", true)
            .defaultVariant("on")
            .contextEvaluator { _, _ -> "does-not-exist" }
            .build()

        val localProvider = InMemoryProvider(mapOf("unknown-variant" to unknownVariantFlag))
        localProvider.initialize(null)

        val eval = localProvider.getBooleanEvaluation("unknown-variant", false, null)
        assertEquals(true, eval.value) // Should fallback to defaultVariant `on` = true
        assertEquals(Reason.ERROR.toString(), eval.reason)
        assertEquals(ErrorCode.GENERAL, eval.errorCode)
        assertEquals("Evaluated variant 'does-not-exist' not found in variants", eval.errorMessage)
        assertEquals("on", eval.variant)
    }
    /* ktlint-enable max-line-length */
}