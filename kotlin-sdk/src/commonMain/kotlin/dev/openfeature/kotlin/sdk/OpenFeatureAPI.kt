@file:JvmName("OpenFeatureAPI")

package dev.openfeature.kotlin.sdk

import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

/**
 * Global singleton entry point for the OpenFeature SDK.
 *
 * Use this directly for typical single-provider usage. For isolated, independent instances
 * (e.g., for DI frameworks or testing), use [createOpenFeatureAPIInstance].
 *
 * This is an instance of [OpenFeatureAPIInstance], just like any instance returned by
 * the [createOpenFeatureAPIInstance] factory method.
 *
 * @apiNote Isolated API instances (per spec section 1.8) are experimental and subject to change.
 */
@JvmField
val OpenFeatureAPI: OpenFeatureAPIInstance = OpenFeatureAPIInstance()

/**
 * Create a new, independent [OpenFeatureAPIInstance] with its own provider, context, hooks,
 * and events — completely isolated from the global [OpenFeatureAPI] singleton and other instances.
 *
 * @return a new [OpenFeatureAPIInstance]
 */
fun createOpenFeatureAPIInstance(): OpenFeatureAPIInstance = OpenFeatureAPIInstance()
