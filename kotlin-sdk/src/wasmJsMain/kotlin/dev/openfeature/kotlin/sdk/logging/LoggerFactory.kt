package dev.openfeature.kotlin.sdk.logging

/**
 * WebAssembly (wasmJs) platform implementation of LoggerFactory.
 * Returns a WasmJsLogger that uses console API for logging.
 */
actual object LoggerFactory {
    actual fun getLogger(tag: String): Logger = WasmJsLogger(tag)
}

/**
 * WebAssembly-specific logger implementation using the console API.
 * Logs are visible in the browser console or Node.js console.
 * Kotlin/Wasm has no `dynamic`, so attributes are appended as `key=value` pairs.
 */
internal class WasmJsLogger(private val tag: String) : Logger {
    override fun debug(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        consoleLog(format(message, attributes, throwable))
    }

    override fun info(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        consoleInfo(format(message, attributes, throwable))
    }

    override fun warn(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        consoleWarn(format(message, attributes, throwable))
    }

    override fun error(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?) {
        consoleError(format(message, attributes, throwable))
    }

    private fun format(message: () -> String, attributes: () -> Map<String, Any?>, throwable: Throwable?): String =
        formatLogLine("[$tag] ${message()}", attributes(), throwable)
}

private fun consoleLog(message: String): Unit = js("console.log(message)")

private fun consoleInfo(message: String): Unit = js("console.info(message)")

private fun consoleWarn(message: String): Unit = js("console.warn(message)")

private fun consoleError(message: String): Unit = js("console.error(message)")