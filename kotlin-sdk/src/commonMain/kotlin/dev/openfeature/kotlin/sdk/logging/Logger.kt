package dev.openfeature.kotlin.sdk.logging

/**
 * Logger interface for OpenFeature SDK logging.
 * Defines a minimal logging contract that can be implemented by platform-specific loggers
 * or used with built-in adapters for common logging frameworks.
 */
interface Logger {
    /**
     * Log a debug message.
     *
     * @param message the message to log
     * @param throwable optional throwable to log with the message
     */
    fun debug(message: String, throwable: Throwable? = null)

    /**
     * Log an info message.
     *
     * @param message the message to log
     * @param throwable optional throwable to log with the message
     */
    fun info(message: String, throwable: Throwable? = null)

    /**
     * Log a warning message.
     *
     * @param message the message to log
     * @param throwable optional throwable to log with the message
     */
    fun warn(message: String, throwable: Throwable? = null)

    /**
     * Log an error message.
     *
     * @param message the message to log
     * @param throwable optional throwable to log with the message
     */
    fun error(message: String, throwable: Throwable? = null)
}

/**
 * A no-op logger that discards all log messages.
 * Used as the default logger when no logger is configured.
 */
class NoOpLogger : Logger {
    override fun debug(message: String, throwable: Throwable?) {}
    override fun info(message: String, throwable: Throwable?) {}
    override fun warn(message: String, throwable: Throwable?) {}
    override fun error(message: String, throwable: Throwable?) {}
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