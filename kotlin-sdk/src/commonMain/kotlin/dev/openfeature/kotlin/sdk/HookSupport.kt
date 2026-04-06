package dev.openfeature.kotlin.sdk

@Suppress("UNCHECKED_CAST") // TODO can we do better here?
class HookSupport {
    fun <T> beforeHooks(
        flagValueType: FlagValueType,
        hookCtx: HookContext<T>,
        hooksWithData: List<Pair<Hook<*>, HookData>>,
        hints: Map<String, Any>
    ) {
        hooksWithData
            .asReversed()
            .forEach { (hook, hookData) ->
                when (flagValueType) {
                    FlagValueType.BOOLEAN -> {
                        safeLet(hook as? Hook<Boolean>, hookCtx as? HookContext<Boolean>) { booleanHook, booleanCtx ->
                            booleanHook.before(booleanCtx.copy(hookData = hookData), hints)
                        }
                    }

                    FlagValueType.STRING -> {
                        safeLet(hook as? Hook<String>, hookCtx as? HookContext<String>) { stringHook, stringCtx ->
                            stringHook.before(stringCtx.copy(hookData = hookData), hints)
                        }
                    }

                    FlagValueType.INTEGER -> {
                        safeLet(hook as? Hook<Int>, hookCtx as? HookContext<Int>) { integerHook, integerCtx ->
                            integerHook.before(integerCtx.copy(hookData = hookData), hints)
                        }
                    }

                    FlagValueType.DOUBLE -> {
                        safeLet(hook as? Hook<Double>, hookCtx as? HookContext<Double>) { doubleHook, doubleCtx ->
                            doubleHook.before(doubleCtx.copy(hookData = hookData), hints)
                        }
                    }

                    FlagValueType.OBJECT -> {
                        safeLet(hook as? Hook<Value>, hookCtx as? HookContext<Value>) { objectHook, objectCtx ->
                            objectHook.before(objectCtx.copy(hookData = hookData), hints)
                        }
                    }
                }
            }
    }

    fun <T> afterHooks(
        flagValueType: FlagValueType,
        hookCtx: HookContext<T>,
        details: FlagEvaluationDetails<T>,
        hooksWithData: List<Pair<Hook<*>, HookData>>,
        hints: Map<String, Any>
    ) {
        hooksWithData
            .forEach { (hook, hookData) ->
                run {
                    when (flagValueType) {
                        FlagValueType.BOOLEAN -> {
                            safeLet(
                                hook as? Hook<Boolean>,
                                hookCtx as? HookContext<Boolean>,
                                details as? FlagEvaluationDetails<Boolean>
                            ) { booleanHook, booleanCtx, booleanDetails ->
                                booleanHook.after(booleanCtx.copy(hookData = hookData), booleanDetails, hints)
                            }
                        }

                        FlagValueType.STRING -> {
                            safeLet(
                                hook as? Hook<String>,
                                hookCtx as? HookContext<String>,
                                details as? FlagEvaluationDetails<String>
                            ) { stringHook, stringCtx, stringDetails ->
                                stringHook.after(stringCtx.copy(hookData = hookData), stringDetails, hints)
                            }
                        }

                        FlagValueType.INTEGER -> {
                            safeLet(
                                hook as? Hook<Int>,
                                hookCtx as? HookContext<Int>,
                                details as? FlagEvaluationDetails<Int>
                            ) { integerHook, integerCtx, integerDetails ->
                                integerHook.after(integerCtx.copy(hookData = hookData), integerDetails, hints)
                            }
                        }

                        FlagValueType.DOUBLE -> {
                            safeLet(
                                hook as? Hook<Double>,
                                hookCtx as? HookContext<Double>,
                                details as? FlagEvaluationDetails<Double>
                            ) { doubleHook, doubleCtx, doubleDetails ->
                                doubleHook.after(doubleCtx.copy(hookData = hookData), doubleDetails, hints)
                            }
                        }

                        FlagValueType.OBJECT -> {
                            safeLet(
                                hook as? Hook<Value>,
                                hookCtx as? HookContext<Value>,
                                details as? FlagEvaluationDetails<Value>
                            ) { objectHook, objectCtx, objectDetails ->
                                objectHook.after(objectCtx.copy(hookData = hookData), objectDetails, hints)
                            }
                        }
                    }
                }
            }
    }

    fun <T> afterAllHooks(
        flagValueType: FlagValueType,
        hookCtx: HookContext<T>,
        details: FlagEvaluationDetails<T>,
        hooksWithData: List<Pair<Hook<*>, HookData>>,
        hints: Map<String, Any>
    ) {
        hooksWithData
            .forEach { (hook, hookData) ->
                run {
                    when (flagValueType) {
                        FlagValueType.BOOLEAN -> {
                            safeLet(
                                hook as? Hook<Boolean>,
                                hookCtx as? HookContext<Boolean>,
                                details as? FlagEvaluationDetails<Boolean>
                            ) { booleanHook, booleanCtx, booleanDetails ->
                                booleanHook.finallyAfter(booleanCtx.copy(hookData = hookData), booleanDetails, hints)
                            }
                        }

                        FlagValueType.STRING -> {
                            safeLet(
                                hook as? Hook<String>,
                                hookCtx as? HookContext<String>,
                                details as? FlagEvaluationDetails<String>
                            ) { stringHook, stringCtx, stringDetails ->
                                stringHook.finallyAfter(stringCtx.copy(hookData = hookData), stringDetails, hints)
                            }
                        }

                        FlagValueType.INTEGER -> {
                            safeLet(
                                hook as? Hook<Int>,
                                hookCtx as? HookContext<Int>,
                                details as? FlagEvaluationDetails<Int>
                            ) { integerHook, integerCtx, integerDetails ->
                                integerHook.finallyAfter(integerCtx.copy(hookData = hookData), integerDetails, hints)
                            }
                        }

                        FlagValueType.DOUBLE -> {
                            safeLet(
                                hook as? Hook<Double>,
                                hookCtx as? HookContext<Double>,
                                details as? FlagEvaluationDetails<Double>
                            ) { doubleHook, doubleCtx, doubleDetails ->
                                doubleHook.finallyAfter(doubleCtx.copy(hookData = hookData), doubleDetails, hints)
                            }
                        }

                        FlagValueType.OBJECT -> {
                            safeLet(
                                hook as? Hook<Value>,
                                hookCtx as? HookContext<Value>,
                                details as? FlagEvaluationDetails<Value>
                            ) { objectHook, objectCtx, objectDetails ->
                                objectHook.finallyAfter(objectCtx.copy(hookData = hookData), objectDetails, hints)
                            }
                        }
                    }
                }
            }
    }

    fun <T> errorHooks(
        flagValueType: FlagValueType,
        hookCtx: HookContext<T>,
        error: Exception,
        hooksWithData: List<Pair<Hook<*>, HookData>>,
        hints: Map<String, Any>
    ) {
        hooksWithData
            .forEach { (hook, hookData) ->
                run {
                    when (flagValueType) {
                        FlagValueType.BOOLEAN -> {
                            safeLet(
                                hook as? Hook<Boolean>,
                                hookCtx as? HookContext<Boolean>
                            ) { booleanHook, booleanCtx ->
                                booleanHook.error(booleanCtx.copy(hookData = hookData), error, hints)
                            }
                        }

                        FlagValueType.STRING -> {
                            safeLet(
                                hook as? Hook<String>,
                                hookCtx as? HookContext<String>
                            ) { stringHook, stringCtx ->
                                stringHook.error(stringCtx.copy(hookData = hookData), error, hints)
                            }
                        }

                        FlagValueType.INTEGER -> {
                            safeLet(
                                hook as? Hook<Int>,
                                hookCtx as? HookContext<Int>
                            ) { integerHook, integerCtx ->
                                integerHook.error(integerCtx.copy(hookData = hookData), error, hints)
                            }
                        }

                        FlagValueType.DOUBLE -> {
                            safeLet(
                                hook as? Hook<Double>,
                                hookCtx as? HookContext<Double>
                            ) { doubleHook, doubleCtx ->
                                doubleHook.error(doubleCtx.copy(hookData = hookData), error, hints)
                            }
                        }

                        FlagValueType.OBJECT -> {
                            safeLet(
                                hook as? Hook<Value>,
                                hookCtx as? HookContext<Value>
                            ) { objectHook, objectCtx ->
                                objectHook.error(objectCtx.copy(hookData = hookData), error, hints)
                            }
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