package dev.openfeature.kotlin.sdk

/**
 * Provider metadata as defined by the OpenFeature specification.
 *
 * In a single provider, `name` identifies the provider. In a Multi-Provider, the outer provider
 * exposes its own `name` and surfaces the metadata of its managed providers via `originalMetadata`,
 * keyed by each provider's resolved unique name.
 *
 * See: https://openfeature.dev/specification/appendix-a/#metadata
 */
interface ProviderMetadata {
    /**
     * Human-readable provider name.
     *
     * - Used in logs, events, and error reporting.
     * - In a Multi-Provider, names must be unique.
     */
    val name: String?

    /**
     * For Multi-Provider: a map of child provider names to their metadata.
     *
     * - For normal providers this MUST be an empty map.
     * - For the Multi-Provider, this contains each inner provider's `ProviderMetadata`, keyed by
     *   that provider's resolved unique name.
     *
     * Example shape:
     * {
     *   "providerA": {...},
     *   "providerB": {...}
     * }
     *
     * See: https://openfeature.dev/specification/appendix-a/#metadata
     */
    val originalMetadata: Map<String, ProviderMetadata>
        get() = emptyMap()
}