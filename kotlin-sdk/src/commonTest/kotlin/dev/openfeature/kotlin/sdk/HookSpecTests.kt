package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Legacy provider with immediate init so [OpenFeatureAPI.setProviderAndWait]
 * // surfaces [OpenFeatureStatus.Ready]
 * // (unlike [NoOpProvider], which is [OpenFeatureStatus.Inactive]). */
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
}