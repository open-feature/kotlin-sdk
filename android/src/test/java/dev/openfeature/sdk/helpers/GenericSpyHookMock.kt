package dev.openfeature.sdk.helpers

import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.HookContext

class GenericSpyHookMock(private var prefix: String = "", var addEval: (String) -> Unit = {}) : Hook<Any> {
    var beforeCalled = 0
    var afterCalled = 0
    var finallyCalledAfter = 0
    var errorCalled = 0

    override fun before(
        ctx: HookContext<Any>,
        hints: Map<String, Any>
    ) {
        beforeCalled += 1
        addEval("$prefix before")
    }

    override fun after(
        ctx: HookContext<Any>,
        details: FlagEvaluationDetails<Any>,
        hints: Map<String, Any>
    ) {
        afterCalled += 1
        addEval("$prefix after")
    }

    override fun error(
        ctx: HookContext<Any>,
        error: Exception,
        hints: Map<String, Any>
    ) {
        errorCalled += 1
        addEval("$prefix error")
    }

    override fun finallyAfter(ctx: HookContext<Any>, hints: Map<String, Any>) {
        finallyCalledAfter += 1
        addEval("$prefix finallyAfter")
    }
}