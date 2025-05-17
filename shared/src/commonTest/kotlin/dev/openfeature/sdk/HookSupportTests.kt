package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.OpenFeatureError.InvalidContextError
import dev.openfeature.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HookSupportTests {
    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testShouldAlwaysCallGenericHook() {
        val metadata = OpenFeatureAPI.getClient().metadata
        val hook = GenericSpyHookMock()
        val hookContext = HookContext(
            "flagKey",
            FlagValueType.BOOLEAN,
            false,
            ImmutableContext(),
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
            FlagEvaluationDetails("", false),
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

        assertEquals(1, hook.beforeCalled)
        assertEquals(1, hook.afterCalled)
        assertEquals(1, hook.finallyCalledAfter)
        assertEquals(1, hook.errorCalled)
    }
}