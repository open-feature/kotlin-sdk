package dev.openfeature.sdk

import dev.openfeature.sdk.helpers.AlwaysBrokenProvider
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

@ExperimentalCoroutinesApi
class HookSpecTests {

    @Test
    fun testNoErrorHookCalled() = runTest {
        OpenFeatureAPI.setProvider(NoOpProvider())
        val client = OpenFeatureAPI.getClient()
        val hook = GenericSpyHookMock()

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hook)))

        Assert.assertEquals(1, hook.beforeCalled)
        Assert.assertEquals(1, hook.afterCalled)
        Assert.assertEquals(0, hook.errorCalled)
        Assert.assertEquals(1, hook.finallyCalledAfter)
    }

    @Test
    fun testErrorHookButNoAfterCalled() = runTest {
        OpenFeatureAPI.setProvider(AlwaysBrokenProvider())
        val client = OpenFeatureAPI.getClient()
        val hook = GenericSpyHookMock()

        client.getBooleanValue("key", false, FlagEvaluationOptions(listOf(hook)))
        Assert.assertEquals(1, hook.beforeCalled)
        Assert.assertEquals(0, hook.afterCalled)
        Assert.assertEquals(1, hook.errorCalled)
        Assert.assertEquals(1, hook.finallyCalledAfter)
    }

    @Test
    fun testHookEvaluationOrder() = runTest {
        val provider = NoOpProvider()
        val evalOrder: MutableList<String> = mutableListOf()
        val addEval: (String) -> Unit = { eval: String -> evalOrder += eval }

        provider.hooks = listOf(GenericSpyHookMock("provider", addEval))
        OpenFeatureAPI.setProvider(provider)
        OpenFeatureAPI.addHooks(listOf(GenericSpyHookMock("api", addEval)))
        val client = OpenFeatureAPI.getClient()
        client.addHooks(listOf(GenericSpyHookMock("client", addEval)))
        val flagOptions = FlagEvaluationOptions(listOf(GenericSpyHookMock("invocation", addEval)))

        client.getBooleanValue("key", false, flagOptions)

        Assert.assertEquals(
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