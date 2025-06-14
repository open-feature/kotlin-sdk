package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HookSpecTests {

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testNoErrorHookCalled() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
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

        val provider = NoOpProvider(
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