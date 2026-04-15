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
 * Attributes are appended to the message as key=value pairs.
 * Suitable for CLI applications and native executables.
 */
internal class NativeLogger(private val tag: String) : Logger {
    private fun prefix(level: String) = "[$level] $tag - "

    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        println(formatLogLine(prefix("DEBUG") + message(), attributes(), throwable))
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        println(formatLogLine(prefix("INFO") + message(), attributes(), throwable))
    }

    // fprintf appends "\n" after the formatted line. When a throwable is present,
    // formatLogLine already ends with "\n<stacktrace>", so the output ends with two
    // newlines. This matches systemd journal conventions where a blank line separates
    // multi-line log entries, and was the behavior before this utility was extracted.
    @OptIn(ExperimentalForeignApi::class)
    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        fprintf(stderr, "%s\n", formatLogLine(prefix("WARN") + message(), attributes(), throwable))
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        fprintf(stderr, "%s\n", formatLogLine(prefix("ERROR") + message(), attributes(), throwable))
    }
}