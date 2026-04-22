package dev.openfeature.kotlin.sdk

interface ClientMetadata {
    @Deprecated("Use domain instead", ReplaceWith("domain"))
    val name: String?

    val domain: String?
}