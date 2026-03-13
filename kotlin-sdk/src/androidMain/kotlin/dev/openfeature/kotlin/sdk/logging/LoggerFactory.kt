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
    override fun debug(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.d(tag, message, throwable)
        } else {
            Log.d(tag, message)
        }
    }

    override fun info(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
    }

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
