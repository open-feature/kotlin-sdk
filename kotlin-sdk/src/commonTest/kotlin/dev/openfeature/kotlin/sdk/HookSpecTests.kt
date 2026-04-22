package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plain [FeatureProvider] (non-SMP) for tests that need the legacy SDK status path
 * or provider hook wiring without using [NoOpProvider]. */
private class LegacyNoOpProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String? = "ready-legacy-test"
    }
) : FeatureProvider {
    override suspend fun initialize(initialContext: EvaluationContext?) {}

    override fun shutdown() {}

    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {}

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())
}

class HookSpecTests {

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testNoErrorHookCalled() = runTest {
        OpenFeatureAPI.setProviderAndWait(LegacyNoOpProvider())
        val client = OpenFeatureAPI.getClient()
        val hook = GenericSpyHookMock()

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hook)))

        assertEquals(1, hook.beforeCalled)
        assertEquals(1, hook.afterCalled)
        assertEquals(0, hook.errorCalled)
        assertEquals(1, hook.finallyCalledAfter)
    }

    @Test
    fun testErrorHookButNoAfterCalled() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        val client = OpenFeatureAPI.getClient()
        val hook = GenericSpyHookMock()

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hook)))
        assertEquals(1, hook.beforeCalled)
        assertEquals(0, hook.afterCalled)
        assertEquals(1, hook.errorCalled)
        assertEquals(1, hook.finallyCalledAfter)
    }

    @Test
    fun testHookEvaluationOrder() = runTest {
        val evalOrder: MutableList<String> = mutableListOf()
        val addEval: (String) -> Unit = { eval: String -> evalOrder += eval }

        val provider = LegacyNoOpProvider(
            hooks = listOf(GenericSpyHookMock("provider", addEval))
        )

        OpenFeatureAPI.setProviderAndWait(provider)
        OpenFeatureAPI.addHooks(listOf(GenericSpyHookMock("api", addEval)))
        val client = OpenFeatureAPI.getClient()
        client.addHooks(listOf(GenericSpyHookMock("client", addEval)))
        val flagOptions = FlagEvaluationOptions(listOf(GenericSpyHookMock("invocation", addEval)))

        client.getBooleanValue("key", false, flagOptions)

        assertEquals(
            listOf(
                "api before",
                "client before",
                "invocation before",
                "provider before",
                "provider after",
                "invocation after",
                "client after",
                "api after",
                "provider finallyAfter",
                "invocation finallyAfter",
                "client finallyAfter",
                "api finallyAfter"
            ),
            evalOrder
        )
    }

    @Test
    fun hookDataIsSharedAcrossStagesForSameHook() = runTest {
        OpenFeatureAPI.setProviderAndWait(LegacyNoOpProvider())
        val client = OpenFeatureAPI.getClient()
        var afterValue: Any? = null
        var finallyValue: Any? = null
        val hook = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                ctx.hookData["span"] = 42
            }

            override fun after(
                ctx: HookContext<Boolean>,
                details: FlagEvaluationDetails<Boolean>,
                hints: Map<String, Any>
            ) {
                afterValue = ctx.hookData["span"]
            }

            override fun finallyAfter(
                ctx: HookContext<Boolean>,
                details: FlagEvaluationDetails<Boolean>,
                hints: Map<String, Any>
            ) {
                finallyValue = ctx.hookData["span"]
            }
        }

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hook)))

        assertEquals(42, afterValue)
        assertEquals(42, finallyValue)
    }

    @Test
    fun hookDataIsNotSharedBetweenDifferentHooks() = runTest {
        OpenFeatureAPI.setProviderAndWait(LegacyNoOpProvider())
        val client = OpenFeatureAPI.getClient()
        val hookA = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                ctx.hookData["onlyA"] = "a"
            }

            override fun after(
                ctx: HookContext<Boolean>,
                details: FlagEvaluationDetails<Boolean>,
                hints: Map<String, Any>
            ) {
                assertEquals("a", ctx.hookData["onlyA"])
                assertNull(ctx.hookData["onlyB"])
            }
        }
        val hookB = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                ctx.hookData["onlyB"] = "b"
            }

            override fun after(
                ctx: HookContext<Boolean>,
                details: FlagEvaluationDetails<Boolean>,
                hints: Map<String, Any>
            ) {
                assertEquals("b", ctx.hookData["onlyB"])
                assertNull(ctx.hookData["onlyA"])
            }
        }

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hookA, hookB)))
    }

    @Test
    fun hookDataPersistsThroughErrorAndFinallyStages() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        val client = OpenFeatureAPI.getClient()
        var errorValue: Any? = null
        var finallyValue: Any? = null
        val hook = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                ctx.hookData["trace"] = "from-before"
            }

            override fun error(ctx: HookContext<Boolean>, error: Exception, hints: Map<String, Any>) {
                errorValue = ctx.hookData["trace"]
            }

            override fun finallyAfter(
                ctx: HookContext<Boolean>,
                details: FlagEvaluationDetails<Boolean>,
                hints: Map<String, Any>
            ) {
                finallyValue = ctx.hookData["trace"]
            }
        }

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hook)))

        assertEquals("from-before", errorValue)
        assertEquals("from-before", finallyValue)
    }
}