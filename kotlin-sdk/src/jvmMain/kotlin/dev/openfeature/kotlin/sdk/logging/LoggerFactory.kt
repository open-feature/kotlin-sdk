package dev.openfeature.kotlin.sdk.logging

import java.time.Instant

/**
 * JVM platform implementation of LoggerFactory.
 * Uses JvmLogger that writes to System.out/err.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger {
        return JvmLogger(tag)
    }
}

/**
 * JVM-specific logger implementation using System.out and System.err.
 * Logs include timestamps and follow standard formatting.
 * Attributes are appended to the message as key=value pairs.
 */
internal class JvmLogger(private val tag: String) : Logger {
    private fun prefix(level: String) = "${Instant.now()} [$level] $tag - "

    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        println(formatLogLine(prefix("DEBUG") + message(), attributes(), throwable))
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        println(formatLogLine(prefix("INFO") + message(), attributes(), throwable))
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        System.err.println(formatLogLine(prefix("WARN") + message(), attributes(), throwable))
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        System.err.println(formatLogLine(prefix("ERROR") + message(), attributes(), throwable))
    }
}