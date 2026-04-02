package dev.openfeature.kotlin.sdk.logging

import platform.Foundation.NSLog

/**
 * iOS platform implementation of LoggerFactory.
 * Returns an IosLogger that uses NSLog for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = IosLogger(tag)
}

/**
 * iOS-specific logger implementation using NSLog.
 * Logs are visible in Xcode console and device logs.
 * NSLog prepends a timestamp automatically, so no additional timestamp is included.
 */
internal class IosLogger(private val tag: String) : Logger {
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String =
        buildString {
            append("[$level] $tag - $message")
            if (throwable != null) {
                append("\n${throwable.stackTraceToString()}")
            }
        }

    override fun debug(throwable: Throwable?, message: () -> String) {
        NSLog(formatMessage("DEBUG", message(), throwable))
    }

    override fun info(throwable: Throwable?, message: () -> String) {
        NSLog(formatMessage("INFO", message(), throwable))
    }

    override fun warn(throwable: Throwable?, message: () -> String) {
        NSLog(formatMessage("WARN", message(), throwable))
    }

    override fun error(throwable: Throwable?, message: () -> String) {
        NSLog(formatMessage("ERROR", message(), throwable))
    }
}