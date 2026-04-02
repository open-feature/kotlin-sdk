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
    override fun debug(throwable: Throwable?, message: () -> String) =
        log(console::log, message(), throwable)

    override fun info(throwable: Throwable?, message: () -> String) =
        log(console::info, message(), throwable)

    override fun warn(throwable: Throwable?, message: () -> String) =
        log(console::warn, message(), throwable)

    override fun error(throwable: Throwable?, message: () -> String) =
        log(console::error, message(), throwable)

    private fun log(logFn: (dynamic) -> Unit, message: String, throwable: Throwable?) {
        if (throwable != null) {
            logFn(arrayOf("[$tag] $message", throwable))
        } else {
            logFn("[$tag] $message")
        }
    }
}