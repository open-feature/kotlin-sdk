package dev.openfeature.kotlin.sdk

/**
 * Global singleton entry point for the OpenFeature SDK.
 *
 * Use this object directly for typical single-provider usage. For isolated, independent instances
 * (e.g., for DI frameworks or testing), use [createOpenFeatureInstance].
 *
 * All methods are inherited from [OpenFeatureInstance].
 */
@Suppress("TooManyFunctions")
object OpenFeatureAPI : OpenFeatureInstance()

/**
 * Create a new, independent [OpenFeatureInstance] with its own provider, context, hooks,
 * and events — completely isolated from the global [OpenFeatureAPI] singleton and other instances.
 *
 * @return a new [OpenFeatureInstance]
 */
fun createOpenFeatureInstance(): OpenFeatureInstance = OpenFeatureInstance()