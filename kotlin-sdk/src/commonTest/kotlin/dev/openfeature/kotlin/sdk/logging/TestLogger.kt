package dev.openfeature.kotlin.sdk.logging

/**
 * A test logger that captures all log messages for verification in tests.
 * This allows tests to verify that the correct messages are logged at the correct levels.
 */
class TestLogger : Logger {
    val debugMessages = mutableListOf<LogEntry>()
    val infoMessages = mutableListOf<LogEntry>()
    val warnMessages = mutableListOf<LogEntry>()
    val errorMessages = mutableListOf<LogEntry>()

    data class LogEntry(val message: String, val throwable: Throwable?)

    override fun debug(message: String, throwable: Throwable?) {
        debugMessages.add(LogEntry(message, throwable))
    }

    override fun info(message: String, throwable: Throwable?) {
        infoMessages.add(LogEntry(message, throwable))
    }

    override fun warn(message: String, throwable: Throwable?) {
        warnMessages.add(LogEntry(message, throwable))
    }

    override fun error(message: String, throwable: Throwable?) {
        errorMessages.add(LogEntry(message, throwable))
    }

    fun clear() {
        debugMessages.clear()
        infoMessages.clear()
        warnMessages.clear()
        errorMessages.clear()
    }

    fun getAllMessages(): List<LogEntry> {
        return debugMessages + infoMessages + warnMessages + errorMessages
    }
}
