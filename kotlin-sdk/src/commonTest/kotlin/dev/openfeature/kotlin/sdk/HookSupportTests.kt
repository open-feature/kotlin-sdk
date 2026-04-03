package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.InvalidContextError
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
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
        val hookData = DefaultHookData()
        val hooksWithData = listOf(hook to hookData)

        hookSupport.beforeHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            hooksWithData,
            mapOf()
        )
        hookSupport.afterHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            FlagEvaluationDetails("", false),
            hooksWithData,
            mapOf()
        )
        hookSupport.afterAllHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            FlagEvaluationDetails("", false),
            hooksWithData,
            mapOf()
        )
        hookSupport.errorHooks(
            FlagValueType.BOOLEAN,
            hookContext,
            InvalidContextError(),
            hooksWithData,
            mapOf()
        )

        assertEquals(1, hook.beforeCalled)
        assertEquals(1, hook.afterCalled)
        assertEquals(1, hook.finallyCalledAfter)
        assertEquals(1, hook.errorCalled)
    }
}