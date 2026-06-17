@file:JvmName("OpenFeatureAPI")

package dev.openfeature.kotlin.sdk

import kotlin.jvm.JvmField
import kotlin.jvm.JvmName

/**
 * Global singleton entry point for the OpenFeature SDK.
 *
 * Use this directly for typical single-provider usage. For isolated, independent instances
 * (e.g., for DI frameworks or testing), use
 * [dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance].
 *
 * This is an instance of [OpenFeatureAPIInstance], just like any instance returned by the
 * factory.
 */
@JvmField
val OpenFeatureAPI: OpenFeatureAPIInstance = OpenFeatureAPIInstance()