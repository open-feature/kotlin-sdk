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
    override fun debug(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.log("[$tag] $message", throwable)
        } else {
            console.log("[$tag] $message")
        }
    }

    override fun info(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.info("[$tag] $message", throwable)
        } else {
            console.info("[$tag] $message")
        }
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.warn("[$tag] $message", throwable)
        } else {
            console.warn("[$tag] $message")
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            console.error("[$tag] $message", throwable)
        } else {
            console.error("[$tag] $message")
        }
    }
}