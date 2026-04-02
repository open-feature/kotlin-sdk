package dev.openfeature.kotlin.sdk.logging

/**
 * JavaScript platform implementation of LoggerFactory.
 * Returns a JsLogger that uses console API for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = JsLogger(tag)
}

/**
 * JavaScript-specific logger implementation using the console API.
 * Logs are visible in the browser console or Node.js console.
 */
internal class JsLogger(private val tag: String) : Logger {
    override fun debug(throwable: Throwable?, message: () -> String) {
        val msg = "[$tag] ${message()}"
        if (throwable != null) console.log(msg, throwable) else console.log(msg)
    }

    override fun info(throwable: Throwable?, message: () -> String) {
        val msg = "[$tag] ${message()}"
        if (throwable != null) console.info(msg, throwable) else console.info(msg)
    }

    override fun warn(throwable: Throwable?, message: () -> String) {
        val msg = "[$tag] ${message()}"
        if (throwable != null) console.warn(msg, throwable) else console.warn(msg)
    }

    override fun error(throwable: Throwable?, message: () -> String) {
        val msg = "[$tag] ${message()}"
        if (throwable != null) console.error(msg, throwable) else console.error(msg)
    }
}