package dev.openfeature.kotlin.sdk.logging

import java.time.Instant

/**
 * JVM platform implementation of LoggerFactory.
 * Automatically detects and uses SLF4J if available on the classpath,
 * otherwise falls back to simple JvmLogger that uses System.out/err.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger {
        // TODO: SLF4J detection will be added in a future PR
        return JvmLogger(tag)
    }
}

/**
 * JVM-specific logger implementation using System.out and System.err.
 * Logs include timestamps and follow standard formatting.
 */
internal class JvmLogger(private val tag: String) : Logger {
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String =
        buildString {
            append("${Instant.now()} [$level] $tag - $message")
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

    override fun warn(throwable: Throwable?, message: () -> String) {
        System.err.println(formatMessage("WARN", message(), throwable))
    }

    override fun error(throwable: Throwable?, message: () -> String) {
        System.err.println(formatMessage("ERROR", message(), throwable))
    }
}