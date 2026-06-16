package dev.openfeature.kotlin.sdk.isolated

import dev.openfeature.kotlin.sdk.OpenFeatureAPIInstance

/**
 * Create a new, independent [OpenFeatureAPIInstance] with its own provider, context, hooks,
 * and events; completely isolated from the global singleton and other instances.
 *
 * Spec 1.8.1: factory function returning a new, independent API instance.
 * Spec 1.8.3: housed in a distinct package from the global singleton.
 *
 * Note: Isolated instances are experimental and subject to change.
 *
 * @return a new [OpenFeatureAPIInstance]
 * @see <a href="https://openfeature.dev/specification/sections/flag-evaluation#18-isolated-api-instances">Spec 1.8</a>
 */
fun createOpenFeatureAPIInstance(): OpenFeatureAPIInstance = OpenFeatureAPIInstance()