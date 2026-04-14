package dev.openfeature.kotlin.sdk

@Suppress("UNCHECKED_CAST") // TODO can we do better here?
internal class HookSupport {
    fun <T> beforeHooks(
        flagValueType: FlagValueType,
        hooksWithContext: List<Pair<Hook<*>, HookContext<T>>>,
        hints: Map<String, Any>
    ) {
        hooksWithContext
            .asReversed()
            .forEach { (hook, ctx) ->
                when (flagValueType) {
                    FlagValueType.BOOLEAN -> {
                        safeLet(hook as? Hook<Boolean>, ctx as? HookContext<Boolean>) { booleanHook, booleanCtx ->
                            booleanHook.before(booleanCtx, hints)
                        }
                    }

                    FlagValueType.STRING -> {
                        safeLet(hook as? Hook<String>, ctx as? HookContext<String>) { stringHook, stringCtx ->
                            stringHook.before(stringCtx, hints)
                        }
                    }

                    FlagValueType.INTEGER -> {
                        safeLet(hook as? Hook<Int>, ctx as? HookContext<Int>) { integerHook, integerCtx ->
                            integerHook.before(integerCtx, hints)
                        }
                    }

                    FlagValueType.LONG -> {
                        safeLet(hook as? Hook<Long>, ctx as? HookContext<Long>) { longHook, longCtx ->
                            longHook.before(longCtx, hints)
                        }
                    }

                    FlagValueType.DOUBLE -> {
                        safeLet(hook as? Hook<Double>, ctx as? HookContext<Double>) { doubleHook, doubleCtx ->
                            doubleHook.before(doubleCtx, hints)
                        }
                    }

                    FlagValueType.OBJECT -> {
                        safeLet(hook as? Hook<Value>, ctx as? HookContext<Value>) { objectHook, objectCtx ->
                            objectHook.before(objectCtx, hints)
                        }
                    }
                }
            }
    }

    fun <T> afterHooks(
        flagValueType: FlagValueType,
        details: FlagEvaluationDetails<T>,
        hooksWithContext: List<Pair<Hook<*>, HookContext<T>>>,
        hints: Map<String, Any>
    ) {
        hooksWithContext.forEach { (hook, ctx) ->
            when (flagValueType) {
                FlagValueType.BOOLEAN -> {
                    safeLet(
                        hook as? Hook<Boolean>,
                        ctx as? HookContext<Boolean>,
                        details as? FlagEvaluationDetails<Boolean>
                    ) { booleanHook, booleanCtx, booleanDetails ->
                        booleanHook.after(booleanCtx, booleanDetails, hints)
                    }
                }

                FlagValueType.STRING -> {
                    safeLet(
                        hook as? Hook<String>,
                        ctx as? HookContext<String>,
                        details as? FlagEvaluationDetails<String>
                    ) { stringHook, stringCtx, stringDetails ->
                        stringHook.after(stringCtx, stringDetails, hints)
                    }
                }

                FlagValueType.INTEGER -> {
                    safeLet(
                        hook as? Hook<Int>,
                        ctx as? HookContext<Int>,
                        details as? FlagEvaluationDetails<Int>
                    ) { integerHook, integerCtx, integerDetails ->
                        integerHook.after(integerCtx, integerDetails, hints)
                    }
                }

                FlagValueType.LONG -> {
                    safeLet(
                        hook as? Hook<Long>,
                        ctx as? HookContext<Long>,
                        details as? FlagEvaluationDetails<Long>
                    ) { longHook, longCtx, longDetails ->
                        longHook.after(longCtx, longDetails, hints)
                    }
                }

                FlagValueType.DOUBLE -> {
                    safeLet(
                        hook as? Hook<Double>,
                        ctx as? HookContext<Double>,
                        details as? FlagEvaluationDetails<Double>
                    ) { doubleHook, doubleCtx, doubleDetails ->
                        doubleHook.after(doubleCtx, doubleDetails, hints)
                    }
                }

                FlagValueType.OBJECT -> {
                    safeLet(
                        hook as? Hook<Value>,
                        ctx as? HookContext<Value>,
                        details as? FlagEvaluationDetails<Value>
                    ) { objectHook, objectCtx, objectDetails ->
                        objectHook.after(objectCtx, objectDetails, hints)
                    }
                }
            }
        }
    }

    fun <T> afterAllHooks(
        flagValueType: FlagValueType,
        details: FlagEvaluationDetails<T>,
        hooksWithContext: List<Pair<Hook<*>, HookContext<T>>>,
        hints: Map<String, Any>
    ) {
        hooksWithContext.forEach { (hook, ctx) ->
            when (flagValueType) {
                FlagValueType.BOOLEAN -> {
                    safeLet(
                        hook as? Hook<Boolean>,
                        ctx as? HookContext<Boolean>,
                        details as? FlagEvaluationDetails<Boolean>
                    ) { booleanHook, booleanCtx, booleanDetails ->
                        booleanHook.finallyAfter(booleanCtx, booleanDetails, hints)
                    }
                }

                FlagValueType.STRING -> {
                    safeLet(
                        hook as? Hook<String>,
                        ctx as? HookContext<String>,
                        details as? FlagEvaluationDetails<String>
                    ) { stringHook, stringCtx, stringDetails ->
                        stringHook.finallyAfter(stringCtx, stringDetails, hints)
                    }
                }

                FlagValueType.INTEGER -> {
                    safeLet(
                        hook as? Hook<Int>,
                        ctx as? HookContext<Int>,
                        details as? FlagEvaluationDetails<Int>
                    ) { integerHook, integerCtx, integerDetails ->
                        integerHook.finallyAfter(integerCtx, integerDetails, hints)
                    }
                }

                FlagValueType.LONG -> {
                    safeLet(
                        hook as? Hook<Long>,
                        ctx as? HookContext<Long>,
                        details as? FlagEvaluationDetails<Long>
                    ) { longHook, longCtx, longDetails ->
                        longHook.finallyAfter(longCtx, longDetails, hints)
                    }
                }

                FlagValueType.DOUBLE -> {
                    safeLet(
                        hook as? Hook<Double>,
                        ctx as? HookContext<Double>,
                        details as? FlagEvaluationDetails<Double>
                    ) { doubleHook, doubleCtx, doubleDetails ->
                        doubleHook.finallyAfter(doubleCtx, doubleDetails, hints)
                    }
                }

                FlagValueType.OBJECT -> {
                    safeLet(
                        hook as? Hook<Value>,
                        ctx as? HookContext<Value>,
                        details as? FlagEvaluationDetails<Value>
                    ) { objectHook, objectCtx, objectDetails ->
                        objectHook.finallyAfter(objectCtx, objectDetails, hints)
                    }
                }
            }
        }
    }

    fun <T> errorHooks(
        flagValueType: FlagValueType,
        error: Exception,
        hooksWithContext: List<Pair<Hook<*>, HookContext<T>>>,
        hints: Map<String, Any>
    ) {
        hooksWithContext.forEach { (hook, ctx) ->
            when (flagValueType) {
                FlagValueType.BOOLEAN -> {
                    safeLet(
                        hook as? Hook<Boolean>,
                        ctx as? HookContext<Boolean>
                    ) { booleanHook, booleanCtx ->
                        booleanHook.error(booleanCtx, error, hints)
                    }
                }

                FlagValueType.STRING -> {
                    safeLet(
                        hook as? Hook<String>,
                        ctx as? HookContext<String>
                    ) { stringHook, stringCtx ->
                        stringHook.error(stringCtx, error, hints)
                    }
                }

                FlagValueType.INTEGER -> {
                    safeLet(
                        hook as? Hook<Int>,
                        ctx as? HookContext<Int>
                    ) { integerHook, integerCtx ->
                        integerHook.error(integerCtx, error, hints)
                    }
                }

                FlagValueType.LONG -> {
                    safeLet(
                        hook as? Hook<Long>,
                        ctx as? HookContext<Long>
                    ) { longHook, longCtx ->
                        longHook.error(longCtx, error, hints)
                    }
                }

                FlagValueType.DOUBLE -> {
                    safeLet(
                        hook as? Hook<Double>,
                        ctx as? HookContext<Double>
                    ) { doubleHook, doubleCtx ->
                        doubleHook.error(doubleCtx, error, hints)
                    }
                }

                FlagValueType.OBJECT -> {
                    safeLet(
                        hook as? Hook<Value>,
                        ctx as? HookContext<Value>
                    ) { objectHook, objectCtx ->
                        objectHook.error(objectCtx, error, hints)
                    }
                }
            }
        }
    }

    private inline fun <T1 : Any, T2 : Any, R : Any> safeLet(p1: T1?, p2: T2?, block: (T1, T2) -> R?): R? {
        return if (p1 != null && p2 != null) block(p1, p2) else null
    }

    private inline fun <T1 : Any, T2 : Any, T3 : Any, R : Any> safeLet(
        p1: T1?,
        p2: T2?,
        p3: T3?,
        block: (T1, T2, T3) -> R?
    ): R? {
        return if (p1 != null && p2 != null && p3 != null) block(p1, p2, p3) else null
    }
}