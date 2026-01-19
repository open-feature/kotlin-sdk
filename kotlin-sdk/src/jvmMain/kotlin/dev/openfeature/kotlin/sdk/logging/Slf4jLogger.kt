package dev.openfeature.kotlin.sdk.logging

import org.slf4j.Logger as Slf4jLoggerInterface

/**
 * Adapter for SLF4J loggers. This allows developers to use their existing
 * SLF4J setup without implementing the Logger interface.
 *
 * Example usage:
 * ```kotlin
 * val slf4jLogger = org.slf4j.LoggerFactory.getLogger("FeatureFlags")
 * val logger = Slf4jLogger(slf4jLogger)
 * OpenFeatureAPI.addHooks(listOf(LoggingHook<Any>(logger = logger)))
 * ```
 */
class Slf4jLogger(private val slf4jLogger: Slf4jLoggerInterface) : Logger {
    override fun debug(message: String, throwable: Throwable?) {
        if (throwable != null) {
            slf4jLogger.debug(message, throwable)
        } else {
            slf4jLogger.debug(message)
        }
    }

    override fun info(message: String, throwable: Throwable?) {
        if (throwable != null) {
            slf4jLogger.info(message, throwable)
        } else {
            slf4jLogger.info(message)
        }
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            slf4jLogger.warn(message, throwable)
        } else {
            slf4jLogger.warn(message)
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            slf4jLogger.error(message, throwable)
        } else {
            slf4jLogger.error(message)
        }
    }

    companion object {
        /**
         * Convenience factory method to create a logger from a logger name.
         */
        fun getLogger(name: String): Logger {
            return Slf4jLogger(org.slf4j.LoggerFactory.getLogger(name))
        }
    }
}
