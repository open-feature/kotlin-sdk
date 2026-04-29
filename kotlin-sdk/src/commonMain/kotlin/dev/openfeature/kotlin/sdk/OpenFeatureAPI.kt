package dev.openfeature.kotlin.sdk

/**
 * Global singleton entry point for the OpenFeature SDK.
 *
 * Use this object directly for typical single-provider usage. For isolated, independent instances
 * (e.g., for DI frameworks or testing), use [createOpenFeatureAPIInstance].
 *
 * All methods are inherited from [OpenFeatureAPIInstance].
 */
@Suppress("TooManyFunctions")
object OpenFeatureAPI : OpenFeatureAPIInstance()

/**
 * Create a new, independent [OpenFeatureAPIInstance] with its own provider, context, hooks,
 * and events — completely isolated from the global [OpenFeatureAPI] singleton and other instances.
 *
 * @return a new [OpenFeatureAPIInstance]
 */
fun createOpenFeatureAPIInstance(): OpenFeatureAPIInstance = OpenFeatureAPIInstance()