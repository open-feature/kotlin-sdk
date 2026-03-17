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
 */
internal class AndroidLogger(private val tag: String) : Logger {
    override fun debug(message: String, throwable: Throwable?) = log(Log.DEBUG, message, throwable)

    override fun info(message: String, throwable: Throwable?) = log(Log.INFO, message, throwable)

    override fun warn(message: String, throwable: Throwable?) = log(Log.WARN, message, throwable)

    override fun error(message: String, throwable: Throwable?) = log(Log.ERROR, message, throwable)

    private fun log(level: Int, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.println(level, tag, message + '\n' + Log.getStackTraceString(throwable))
        } else {
            Log.println(level, tag, message)
        }
    }
}