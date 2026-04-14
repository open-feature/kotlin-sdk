package dev.openfeature.kotlin.sdk.logging

/**
 * Log levels supported by the [Logger] interface.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Logger interface for OpenFeature SDK logging.
 * Defines a minimal logging contract that can be implemented by platform-specific loggers
 * or used with built-in adapters for common logging frameworks.
 *
 * Both the message and attributes are lambdas so evaluation is deferred until the logger
 * decides to emit the log record. This avoids unnecessary work when logging is inactive
 * (e.g. with [NoOpLogger]).
 *
 * Implementations that support structured logging (e.g. SLF4J, Android structured logs)
 * can forward the attributes map directly. Implementations that only support plain strings
 * are responsible for stringifying the attributes themselves.
 *
 * ```kotlin
 * logger.debug({ "Flag evaluation starting" }, { mapOf("flag" to flagKey) })
 * logger.error({ "Evaluation failed" }, { mapOf("flag" to flagKey) }, exception)
 * ```
 */
interface Logger {
    /**
     * Log a debug message.
     *
     * @param message lambda producing the log message
     * @param attributes lambda producing a map of structured key-value pairs
     * @param throwable optional throwable to associate with the log record
     */
    fun debug(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)

    /**
     * Log an info message.
     *
     * @param message lambda producing the log message
     * @param attributes lambda producing a map of structured key-value pairs
     * @param throwable optional throwable to associate with the log record
     */
    fun info(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)

    /**
     * Log a warning message.
     *
     * @param message lambda producing the log message
     * @param attributes lambda producing a map of structured key-value pairs
     * @param throwable optional throwable to associate with the log record
     */
    fun warn(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)

    /**
     * Log an error message.
     *
     * @param message lambda producing the log message
     * @param attributes lambda producing a map of structured key-value pairs
     * @param throwable optional throwable to associate with the log record
     */
    fun error(message: () -> String, attributes: () -> Map<String, Any?> = { emptyMap() }, throwable: Throwable? = null)
}

/**
 * A no-op logger that discards all log messages.
 * Used as the default logger when no logger is configured.
 * Neither lambda is evaluated.
 */
class NoOpLogger : Logger {
    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {}
    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {}
    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {}
    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {}
}

/**
 * Formats a log line by appending structured attributes as `key=value` pairs and,
 * if present, the throwable stack trace. Used by string-only logging backends
 * (Android Logcat, JVM stdout, iOS NSLog, Linux stderr) that have no native
 * structured key-value API.
 *
 * @param message the pre-built message string (may already include a level/tag prefix)
 * @param attributes key-value pairs to append after the message
 * @param throwable optional throwable whose stack trace is appended on a new line
 */
internal fun formatLogLine(
    message: String,
    attributes: Map<String, Any?>,
    throwable: Throwable? = null
): String = buildString {
    append(message)
    if (attributes.isNotEmpty()) {
        append(" ")
        append(attributes.entries.joinToString(" ") { "${it.key}=${it.value}" })
    }
    if (throwable != null) {
        append("\n${throwable.stackTraceToString()}")
    }
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