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
 * Attributes are appended to the message as key=value pairs.
 */
internal class IosLogger(private val tag: String) : Logger {
    private fun prefix(level: String) = "[$level] $tag - "

    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        NSLog("%@", formatLogLine(prefix("DEBUG") + message(), attributes(), throwable))
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        NSLog("%@", formatLogLine(prefix("INFO") + message(), attributes(), throwable))
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        NSLog("%@", formatLogLine(prefix("WARN") + message(), attributes(), throwable))
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        NSLog("%@", formatLogLine(prefix("ERROR") + message(), attributes(), throwable))
    }
}