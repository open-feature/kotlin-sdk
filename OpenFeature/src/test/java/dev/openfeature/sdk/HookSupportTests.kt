package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.OpenFeatureError.InvalidContextError
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import org.junit.Assert
import org.junit.Test

class HookSupportTests {
    @Test
    fun testShouldAlwaysCallGenericHook() {
        val metadata = OpenFeatureAPI.getClient().metadata
        val hook = GenericSpyHookMock()
        val hookContext = HookContext(
            "flagKey",
            FlagValueType.BOOLEAN,
            false,
            MutableContext(),
            metadata,
            NoOpProvider().metadata
        )

        val hookSupport = HookSupport()

        hookSupport.beforeHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            listOf(hook),
            mapOf()
        )
        hookSupport.afterHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            FlagEvaluationDetails("", false),
            listOf(hook),
            mapOf()
        )
        hookSupport.afterAllHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            listOf(hook),
            mapOf()
        )
        hookSupport.errorHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            InvalidContextError(),
            listOf(hook),
            mapOf()
        )

        Assert.assertEquals(1, hook.beforeCalled)
        Assert.assertEquals(1, hook.afterCalled)
        Assert.assertEquals(1, hook.finallyCalledAfter)
        Assert.assertEquals(1, hook.errorCalled)
    }
}
