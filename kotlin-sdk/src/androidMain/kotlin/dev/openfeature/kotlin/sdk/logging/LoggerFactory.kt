package dev.openfeature.kotlin.sdk.logging

import android.util.Log

/**
 * Android platform implementation of LoggerFactory.
 * Returns an AndroidLogger that uses android.util.Log for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = AndroidLogger(tag)
}

/**
 * Android-specific logger implementation using android.util.Log.
 * Logs are visible in Logcat.
 * Attributes are appended to the message as key=value pairs.
 */
internal class AndroidLogger(private val tag: String) : Logger {
    // throwable is passed directly to Log.*() which formats the stack trace natively.
    // Do NOT pass throwable to formatLogLine here — it would duplicate the stack trace in Logcat.
    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!Log.isLoggable(tag, Log.DEBUG)) return
        val msg = formatLogLine(message(), attributes())
        if (throwable != null) Log.d(tag, msg, throwable) else Log.d(tag, msg)
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!Log.isLoggable(tag, Log.INFO)) return
        val msg = formatLogLine(message(), attributes())
        if (throwable != null) Log.i(tag, msg, throwable) else Log.i(tag, msg)
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!Log.isLoggable(tag, Log.WARN)) return
        val msg = formatLogLine(message(), attributes())
        if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        if (!Log.isLoggable(tag, Log.ERROR)) return
        val msg = formatLogLine(message(), attributes())
        if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
    }
}