package dev.openfeature.kotlin.sdk.logging

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
 */
internal class IosLogger(private val tag: String) : Logger {
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String {
        val timestamp = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return buildString {
            append("$timestamp [$level] $tag - $message")
            if (throwable != null) {
                append("\n${throwable.stackTraceToString()}")
            }
        }
    }

    override fun debug(message: String, throwable: Throwable?) {
        NSLog(formatMessage("DEBUG", message, throwable))
    }

    override fun info(message: String, throwable: Throwable?) {
        NSLog(formatMessage("INFO", message, throwable))
    }

    override fun warn(message: String, throwable: Throwable?) {
        NSLog(formatMessage("WARN", message, throwable))
    }

    override fun error(message: String, throwable: Throwable?) {
        NSLog(formatMessage("ERROR", message, throwable))
    }
}
