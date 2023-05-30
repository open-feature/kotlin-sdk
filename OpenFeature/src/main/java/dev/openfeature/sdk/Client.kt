package dev.openfeature.sdk

interface Client : Features {
    val metadata: Metadata
    val hooks: List<Hook<*>>

    fun addHooks(hooks: List<Hook<*>>)
}