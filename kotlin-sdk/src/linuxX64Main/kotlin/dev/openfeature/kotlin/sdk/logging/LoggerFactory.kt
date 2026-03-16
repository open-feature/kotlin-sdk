package dev.openfeature.kotlin.sdk.logging

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fprintf
import platform.posix.stderr

/**
 * Linux Native platform implementation of LoggerFactory.
 * Returns a NativeLogger that uses println for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = NativeLogger(tag)
}

/**
 * Native-specific logger implementation using println for stdout and fprintf for stderr.
 * Warnings and errors are written to stderr following POSIX conventions.
 * Suitable for CLI applications and native executables.
 */
internal class NativeLogger(private val tag: String) : Logger {
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String {
        return buildString {
            append("[$level] $tag - $message")
            if (throwable != null) {
                append("\n${throwable.stackTraceToString()}")
            }
        }
    }

    override fun debug(message: String, throwable: Throwable?) {
        println(formatMessage("DEBUG", message, throwable))
    }

    override fun info(message: String, throwable: Throwable?) {
        println(formatMessage("INFO", message, throwable))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun warn(message: String, throwable: Throwable?) {
        fprintf(stderr, "%s\n", formatMessage("WARN", message, throwable))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun error(message: String, throwable: Throwable?) {
        fprintf(stderr, "%s\n", formatMessage("ERROR", message, throwable))
    }
}
