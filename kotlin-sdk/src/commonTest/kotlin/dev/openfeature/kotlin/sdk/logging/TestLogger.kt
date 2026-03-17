package dev.openfeature.kotlin.sdk.logging

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A test logger that captures all log messages for verification in tests.
 * This allows tests to verify that the correct messages are logged at the correct levels.
 *
 * This implementation is thread-safe to prevent ConcurrentModificationException
 * when hooks are invoked from multiple coroutines during testing.
 *
 * Messages are stored in both level-specific lists and a combined chronological list
 * to allow efficient access without repeated filtering.
 */
class TestLogger : Logger {
    private val lock = SynchronizedObject()

    private val _debugMessages = mutableListOf<LogEntry>()
    private val _infoMessages = mutableListOf<LogEntry>()
    private val _warnMessages = mutableListOf<LogEntry>()
    private val _errorMessages = mutableListOf<LogEntry>()
    private val _allMessages = mutableListOf<LogEntry>()

    val debugMessages: List<LogEntry> get() = synchronized(lock) { _debugMessages.toList() }
    val infoMessages: List<LogEntry> get() = synchronized(lock) { _infoMessages.toList() }
    val warnMessages: List<LogEntry> get() = synchronized(lock) { _warnMessages.toList() }
    val errorMessages: List<LogEntry> get() = synchronized(lock) { _errorMessages.toList() }

    data class LogEntry(val message: String, val throwable: Throwable?)

    override fun debug(message: String, throwable: Throwable?) {
        val entry = LogEntry(message, throwable)
        synchronized(lock) {
            _debugMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    override fun info(message: String, throwable: Throwable?) {
        val entry = LogEntry(message, throwable)
        synchronized(lock) {
            _infoMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    override fun warn(message: String, throwable: Throwable?) {
        val entry = LogEntry(message, throwable)
        synchronized(lock) {
            _warnMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        val entry = LogEntry(message, throwable)
        synchronized(lock) {
            _errorMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    fun clear() {
        synchronized(lock) {
            _debugMessages.clear()
            _infoMessages.clear()
            _warnMessages.clear()
            _errorMessages.clear()
            _allMessages.clear()
        }
    }

    fun getAllMessages(): List<LogEntry> {
        return synchronized(lock) { _allMessages.toList() }
    }
}