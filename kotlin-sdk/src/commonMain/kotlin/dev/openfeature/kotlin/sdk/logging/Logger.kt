package dev.openfeature.kotlin.sdk.logging

/**
 * Logger interface for OpenFeature SDK logging.
 * Defines a minimal logging contract that can be implemented by platform-specific loggers
 * or used with built-in adapters for common logging frameworks.
 *
 * Message parameters are lambdas so evaluation is deferred until the logger decides to
 * emit the message. This avoids unnecessary string construction when logging is inactive
 * (e.g. with [NoOpLogger]).
 *
 * The throwable parameter comes before the message lambda so callers can use trailing
 * lambda syntax:
 * ```kotlin
 * logger.debug { "simple message" }
 * logger.error(exception) { "message with cause" }
 * ```
 */
interface Logger {
    /**
     * Log a debug message.
     *
     * @param throwable optional throwable to log with the message
     * @param message lambda producing the message to log
     */
    fun debug(throwable: Throwable? = null, message: () -> String)

    /**
     * Log an info message.
     *
     * @param throwable optional throwable to log with the message
     * @param message lambda producing the message to log
     */
    fun info(throwable: Throwable? = null, message: () -> String)

    /**
     * Log a warning message.
     *
     * @param throwable optional throwable to log with the message
     * @param message lambda producing the message to log
     */
    fun warn(throwable: Throwable? = null, message: () -> String)

    /**
     * Log an error message.
     *
     * @param throwable optional throwable to log with the message
     * @param message lambda producing the message to log
     */
    fun error(throwable: Throwable? = null, message: () -> String)
}

/**
 * A no-op logger that discards all log messages.
 * Used as the default logger when no logger is configured.
 * Message lambdas are never evaluated.
 */
class NoOpLogger : Logger {
    override fun debug(throwable: Throwable?, message: () -> String) {}
    override fun info(throwable: Throwable?, message: () -> String) {}
    override fun warn(throwable: Throwable?, message: () -> String) {}
    override fun error(throwable: Throwable?, message: () -> String) {}
}

/**
 * Factory for creating platform-specific default loggers.
 */
expect object LoggerFactory {
    /**
     * Get a platform-specific logger instance.
     *
     * @param tag the tag to use for logging (e.g., "OpenFeature", "MyApp")
     * @return a platform-specific logger implementation
     */
    fun getLogger(tag: String = "OpenFeature"): Logger
}