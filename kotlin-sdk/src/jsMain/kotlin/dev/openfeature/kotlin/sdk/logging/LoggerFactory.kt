package dev.openfeature.kotlin.sdk.logging

/**
 * JavaScript platform implementation of LoggerFactory.
 * Returns a JsLogger that uses console API for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = JsLogger(tag)
}

/**
 * JavaScript-specific logger implementation using the console API.
 * Logs are visible in the browser console or Node.js console.
 * Attributes are converted to a plain JS object so browser devtools
 * display them as an expandable key-value structure.
 */
internal class JsLogger(private val tag: String) : Logger {
    // Convert to a plain JS object so devtools shows {key: value} not Kotlin internals.
    private fun toJsObject(attrs: Map<String, Any?>): dynamic {
        val obj = js("{}")
        attrs.forEach { (k, v) -> obj[k] = v }
        return obj
    }

    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val msg = "[$tag] ${message()}"
        val attrs = attributes()
        if (throwable != null) {
            if (attrs.isNotEmpty()) {
                console.log(msg, toJsObject(attrs), throwable)
            } else {
                console.log(msg, throwable)
            }
        } else if (attrs.isNotEmpty()) {
            console.log(msg, toJsObject(attrs))
        } else {
            console.log(msg)
        }
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val msg = "[$tag] ${message()}"
        val attrs = attributes()
        if (throwable != null) {
            if (attrs.isNotEmpty()) {
                console.info(msg, toJsObject(attrs), throwable)
            } else {
                console.info(msg, throwable)
            }
        } else if (attrs.isNotEmpty()) {
            console.info(msg, toJsObject(attrs))
        } else {
            console.info(msg)
        }
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val msg = "[$tag] ${message()}"
        val attrs = attributes()
        if (throwable != null) {
            if (attrs.isNotEmpty()) {
                console.warn(msg, toJsObject(attrs), throwable)
            } else {
                console.warn(msg, throwable)
            }
        } else if (attrs.isNotEmpty()) {
            console.warn(msg, toJsObject(attrs))
        } else {
            console.warn(msg)
        }
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        val msg = "[$tag] ${message()}"
        val attrs = attributes()
        if (throwable != null) {
            if (attrs.isNotEmpty()) {
                console.error(msg, toJsObject(attrs), throwable)
            } else {
                console.error(msg, throwable)
            }
        } else if (attrs.isNotEmpty()) {
            console.error(msg, toJsObject(attrs))
        } else {
            console.error(msg)
        }
    }
}