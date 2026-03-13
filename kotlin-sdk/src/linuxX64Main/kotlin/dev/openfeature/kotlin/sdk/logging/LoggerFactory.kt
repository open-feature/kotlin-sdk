package dev.openfeature.kotlin.sdk.logging

/**
 * Linux Native platform implementation of LoggerFactory.
 * Returns a NativeLogger that uses println for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = NativeLogger(tag)
}

/**
 * Native-specific logger implementation using println.
 * Suitable for CLI applications and native executables.
 */
internal class NativeLogger(private val tag: String) : Logger {
    private fun formatMessage(level: String, message: String, throwable: Throwable?): String {
        return buildString {
            append("[$level] $tag - $message")
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
        println(formatMessage("WARN", message, throwable))
    }

    override fun error(message: String, throwable: Throwable?) {
        println(formatMessage("ERROR", message, throwable))
    }
}
