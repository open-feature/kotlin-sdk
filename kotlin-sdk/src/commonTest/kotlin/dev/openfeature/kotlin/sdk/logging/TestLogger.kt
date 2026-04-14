package dev.openfeature.kotlin.sdk.logging

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A test logger that captures all log messages for verification in tests.
 * This allows tests to verify that the correct messages are logged at the correct levels,
 * with the correct structured attributes.
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

    data class LogEntry(val message: String, val attributes: Map<String, Any?>, val throwable: Throwable?)

    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        // Lambdas are evaluated before acquiring the lock. In this test context they only
        // capture immutable hook context data, so there is no data race in practice.
        // Evaluating inside the lock would be unsafe if a lambda itself tried to acquire
        // another lock, which simple test lambdas never do.
        val entry = LogEntry(message(), attributes(), throwable)
        synchronized(lock) {
            _debugMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val entry = LogEntry(message(), attributes(), throwable)
        synchronized(lock) {
            _infoMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val entry = LogEntry(message(), attributes(), throwable)
        synchronized(lock) {
            _warnMessages.add(entry)
            _allMessages.add(entry)
        }
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val entry = LogEntry(message(), attributes(), throwable)
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