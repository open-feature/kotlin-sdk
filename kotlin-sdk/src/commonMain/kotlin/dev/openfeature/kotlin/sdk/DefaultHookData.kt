package dev.openfeature.kotlin.sdk

import kotlin.reflect.KClass

internal class DefaultHookData(
    private val backing: MutableMap<String, Any?> = mutableMapOf()
) : HookData {

    override operator fun set(key: String, value: Any?) {
        backing[key] = value
    }

    override operator fun get(key: String): Any? = backing[key]

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: String, type: KClass<T>): T? {
        val value = backing[key] ?: return null
        if (!type.isInstance(value)) {
            throw ClassCastException(
                "${value::class.simpleName} cannot be cast to ${type.simpleName}"
            )
        }
        return value as T
    }
}