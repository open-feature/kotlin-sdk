package dev.openfeature.kotlin.sdk.hooks

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.logging.LogLevel
import dev.openfeature.kotlin.sdk.logging.Logger
import dev.openfeature.kotlin.sdk.logging.NoOpLogger
import kotlin.time.ExperimentalTime

/**
 * A hook that logs detailed information during flag evaluation lifecycle.
 *
 * Logs at different stages:
 * - Before: Flag evaluation request
 * - After: Successful evaluation with result
 * - Error: Errors during evaluation
 * - Finally: Completion status
 *
 * @param logger The logger to use. Defaults to NoOpLogger.
 * @param logEvaluationContext If true, includes evaluation context in logs (default: false for privacy)
 * @param beforeLogLevel Log level for the before stage (default: DEBUG)
 * @param afterLogLevel Log level for the after stage (default: DEBUG)
 * @param errorLogLevel Log level for the error stage (default: ERROR)
 * @param finallyLogLevel Log level for the finallyAfter stage (default: DEBUG)
 */
class LoggingHook(
    private val logger: Logger = NoOpLogger(),
    private val logEvaluationContext: Boolean = false,
    private val beforeLogLevel: LogLevel = LogLevel.DEBUG,
    private val afterLogLevel: LogLevel = LogLevel.DEBUG,
    private val errorLogLevel: LogLevel = LogLevel.ERROR,
    private val finallyLogLevel: LogLevel = LogLevel.DEBUG
) : Hook<Any> {

    companion object {
        /**
         * Hook hint key to enable/disable context logging for a specific evaluation.
         * Pass this key in hookHints with a Boolean value to override the hook's default behavior.
         */
        const val HINT_LOG_EVALUATION_CONTEXT = "logEvaluationContext"
    }

    override fun before(ctx: HookContext<Any>, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        val message = buildString {
            append("Flag evaluation starting: ")
            append("flag='${ctx.flagKey}', ")
            append("type=${ctx.type}, ")
            append("defaultValue=${formatAnyValue(ctx.defaultValue)}")
            if (shouldLogContext && ctx.ctx != null) {
                append(", ")
                append(formatContext(ctx.ctx))
            }
            append(", provider='${ctx.providerMetadata.name}'")
            if (ctx.clientMetadata?.name != null) {
                append(", client='${ctx.clientMetadata.name}'")
            }
        }

        logAtLevel(beforeLogLevel) { message }
    }

    override fun after(ctx: HookContext<Any>, details: FlagEvaluationDetails<Any>, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        val message = buildString {
            append("Flag evaluation completed: ")
            append("flag='${details.flagKey}', ")
            append("value=${formatAnyValue(details.value)}")
            if (details.variant != null) {
                append(", variant='${details.variant}'")
            }
            if (details.reason != null) {
                append(", reason='${details.reason}'")
            }
            if (shouldLogContext && ctx.ctx != null) {
                append(", ")
                append(formatContext(ctx.ctx))
            }
            append(", provider='${ctx.providerMetadata.name}'")
        }

        logAtLevel(afterLogLevel) { message }
    }

    override fun error(ctx: HookContext<Any>, error: Exception, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        val message = buildString {
            append("Flag evaluation error: ")
            append("flag='${ctx.flagKey}', ")
            append("type=${ctx.type}, ")
            append("defaultValue=${formatAnyValue(ctx.defaultValue)}")
            if (shouldLogContext && ctx.ctx != null) {
                append(", ")
                append(formatContext(ctx.ctx))
            }
            append(", provider='${ctx.providerMetadata.name}', ")
            append("error='${error.message?.replace("'", "''")}'")
        }

        logAtLevel(errorLogLevel, error) { message }
    }

    override fun finallyAfter(ctx: HookContext<Any>, details: FlagEvaluationDetails<Any>, hints: Map<String, Any>) {
        val message = buildString {
            append("Flag evaluation finalized: ")
            append("flag='${ctx.flagKey}'")
            if (details.errorCode != null) {
                append(", errorCode=${details.errorCode}")
            }
            if (details.errorMessage != null) {
                append(", errorMessage='${details.errorMessage.replace("'", "''")}'")
            }
        }

        logAtLevel(finallyLogLevel) { message }
    }

    private fun logAtLevel(level: LogLevel, throwable: Throwable? = null, message: () -> String) {
        when (level) {
            LogLevel.DEBUG -> logger.debug(throwable, message)
            LogLevel.INFO -> logger.info(throwable, message)
            LogLevel.WARN -> logger.warn(throwable, message)
            LogLevel.ERROR -> logger.error(throwable, message)
        }
    }

    private fun formatContext(context: EvaluationContext): String {
        return buildString {
            append("context={")
            append("targetingKey='${context.getTargetingKey()}'")
            val attributes = context.asMap()
            if (attributes.isNotEmpty()) {
                append(", attributes={")
                append(attributes.entries.joinToString(", ") { "${it.key}=${formatValue(it.value)}" })
                append("}")
            }
            append("}")
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun formatValue(value: Value): String {
        return when (value) {
            is Value.String -> "'${value.string.replace("'", "''")}'"
            is Value.Integer -> value.integer.toString()
            is Value.Double -> value.double.toString()
            is Value.Boolean -> value.boolean.toString()
            is Value.Instant -> value.instant.toString()
            is Value.List -> "[${value.list.joinToString(", ") { formatValue(it) }}]"
            is Value.Structure ->
                "{${value.structure.entries.joinToString(", ") { "${it.key}=${formatValue(it.value)}" }}}"
            is Value.Null -> "null"
        }
    }

    private fun formatAnyValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "'${value.replace("'", "''")}'"
            is Number, is Boolean -> value.toString()
            else -> "'${value.toString().replace("'", "''")}'"
        }
    }
}