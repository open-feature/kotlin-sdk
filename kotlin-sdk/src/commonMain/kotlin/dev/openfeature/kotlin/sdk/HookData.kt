package dev.openfeature.kotlin.sdk

import kotlin.reflect.KClass

class HookData {
    private val backing = mutableMapOf<String, Any?>()

    operator fun set(key: String, value: Any?) {
        backing[key] = value
    }

    operator fun get(key: String): Any? = backing[key]

    /**
     * Gets the value for the given key, cast to the specified type.
     *
     * @return the value cast to [type], or null if not found
     * @throws ClassCastException if the value cannot be cast to [type]
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, type: KClass<T>): T? {
        val value = backing[key] ?: return null
        if (!type.isInstance(value)) {
            throw ClassCastException(
                "${value::class.simpleName} cannot be cast to ${type.simpleName}"
            )
        }
        return value as T
    }
}