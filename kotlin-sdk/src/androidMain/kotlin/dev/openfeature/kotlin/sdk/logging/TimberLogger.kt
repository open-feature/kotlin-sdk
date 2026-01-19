package dev.openfeature.kotlin.sdk.logging

import timber.log.Timber

/**
 * Adapter for Timber (popular Android logging library).
 * Allows developers to use their existing Timber setup.
 *
 * Example usage:
 * ```kotlin
 * Timber.plant(Timber.DebugTree())
 * val logger = TimberLogger()
 * OpenFeatureAPI.addHooks(listOf(LoggingHook<Any>(logger = logger)))
 * ```
 */
class TimberLogger : Logger {
    override fun debug(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Timber.d(throwable, message)
        } else {
            Timber.d(message)
        }
    }

    override fun info(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Timber.i(throwable, message)
        } else {
            Timber.i(message)
        }
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Timber.w(throwable, message)
        } else {
            Timber.w(message)
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Timber.e(throwable, message)
        } else {
            Timber.e(message)
        }
    }
}
