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
 * Messages are stored in chronological order across all log levels, allowing tests
 * to verify the exact sequence of logged events.
 */
class TestLogger : Logger {
    private val lock = SynchronizedObject()

    private enum class Level { DEBUG, INFO, WARN, ERROR }

    private data class InternalLogEntry(val level: Level, val entry: LogEntry)

    private val _messages = mutableListOf<InternalLogEntry>()

    val debugMessages: List<LogEntry> get() = synchronized(lock) {
        _messages.filter { it.level == Level.DEBUG }.map { it.entry }
    }
    val infoMessages: List<LogEntry> get() = synchronized(lock) {
        _messages.filter { it.level == Level.INFO }.map { it.entry }
    }
    val warnMessages: List<LogEntry> get() = synchronized(lock) {
        _messages.filter { it.level == Level.WARN }.map { it.entry }
    }
    val errorMessages: List<LogEntry> get() = synchronized(lock) {
        _messages.filter { it.level == Level.ERROR }.map { it.entry }
    }

    data class LogEntry(val message: String, val throwable: Throwable?)

    override fun debug(message: String, throwable: Throwable?) {
        synchronized(lock) { _messages.add(InternalLogEntry(Level.DEBUG, LogEntry(message, throwable))) }
    }

    override fun info(message: String, throwable: Throwable?) {
        synchronized(lock) { _messages.add(InternalLogEntry(Level.INFO, LogEntry(message, throwable))) }
    }

    override fun warn(message: String, throwable: Throwable?) {
        synchronized(lock) { _messages.add(InternalLogEntry(Level.WARN, LogEntry(message, throwable))) }
    }

    override fun error(message: String, throwable: Throwable?) {
        synchronized(lock) { _messages.add(InternalLogEntry(Level.ERROR, LogEntry(message, throwable))) }
    }

    fun clear() {
        synchronized(lock) {
            _messages.clear()
        }
    }

    fun getAllMessages(): List<LogEntry> {
        return synchronized(lock) {
            _messages.map { it.entry }
        }
    }
}