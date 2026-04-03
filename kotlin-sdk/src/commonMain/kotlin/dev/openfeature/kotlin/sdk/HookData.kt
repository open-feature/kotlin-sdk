package dev.openfeature.kotlin.sdk

import kotlin.reflect.KClass

interface HookData {
    fun set(key: String, value: Any?)

    fun get(key: String): Any?

    /**
     * Gets the value for the given key, cast to the specified type.
     *
     * @return the value cast to [type], or null if not found
     * @throws ClassCastException if the value cannot be cast to [type]
     */
    fun <T : Any> get(key: String, type: KClass<T>): T?
}
