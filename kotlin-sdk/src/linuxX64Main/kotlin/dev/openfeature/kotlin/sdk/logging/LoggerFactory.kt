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
 * Timestamps are omitted as Linux deployments typically run under systemd or similar
 * which provide their own timestamping.
 * Suitable for CLI applications and native executables.
 */
internal class NativeLogger(private val tag: String) : Logger {
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String =
        buildString {
            append("[$level] $tag - $message")
            if (throwable != null) {
                append("\n${throwable.stackTraceToString()}")
            }
        }

    override fun debug(throwable: Throwable?, message: () -> String) {
        println(formatMessage("DEBUG", message(), throwable))
    }

    override fun info(throwable: Throwable?, message: () -> String) {
        println(formatMessage("INFO", message(), throwable))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun warn(throwable: Throwable?, message: () -> String) {
        fprintf(stderr, "%s\n", formatMessage("WARN", message(), throwable))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun error(throwable: Throwable?, message: () -> String) {
        fprintf(stderr, "%s\n", formatMessage("ERROR", message(), throwable))
    }
}