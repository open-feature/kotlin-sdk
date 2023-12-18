package dev.openfeature.sdk

interface Hook<T> {
    fun before(ctx: HookContext<T>, hints: Map<String, Any>) = Unit
    fun after(ctx: HookContext<T>, details: FlagEvaluationDetails<T>, hints: Map<String, Any>) = Unit
    fun error(ctx: HookContext<T>, error: Exception, hints: Map<String, Any>) = Unit
    fun finallyAfter(ctx: HookContext<T>, hints: Map<String, Any>) = Unit
    fun supportsFlagValueType(flagValueType: FlagValueType): Boolean = true
}