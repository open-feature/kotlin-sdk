package dev.openfeature.sdk

interface Client : Features {
    val metadata: ClientMetadata
    val hooks: List<Hook<*>>

    fun addHooks(hooks: List<Hook<*>>)
}