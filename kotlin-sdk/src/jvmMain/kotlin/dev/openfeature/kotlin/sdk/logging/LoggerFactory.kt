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
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String {
        return buildString {
            append("${Instant.now()} [$level] $tag - $message")
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

    override fun warn(message: String, throwable: Throwable?) {
        System.err.println(formatMessage("WARN", message, throwable))
    }

    override fun error(message: String, throwable: Throwable?) {
        System.err.println(formatMessage("ERROR", message, throwable))
    }
}
