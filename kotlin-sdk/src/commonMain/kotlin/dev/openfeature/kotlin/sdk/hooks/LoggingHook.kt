@file:OptIn(ExperimentalTime::class)

package dev.openfeature.kotlin.sdk.hooks

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.logging.Logger
import dev.openfeature.kotlin.sdk.logging.NoOpLogger
import kotlin.time.ExperimentalTime

/**
 * A hook that logs detailed information during flag evaluation lifecycle.
 *
 * Logs at different stages:
 * - Before: Flag evaluation request (debug level)
 * - After: Successful evaluation with result (debug level)
 * - Error: Errors during evaluation (error level)
 * - Finally: Completion status (debug level)
 *
 * @param logger The logger to use. Defaults to NoOpLogger.
 * @param logEvaluationContext If true, includes evaluation context in logs (default: false for privacy)
 */
class LoggingHook<T>(
    private val logger: Logger = NoOpLogger(),
    private val logEvaluationContext: Boolean = false
) : Hook<T> {

    companion object {
        /**
         * Hook hint key to enable/disable context logging for a specific evaluation.
         * Pass this key in hookHints with a Boolean value to override the hook's default behavior.
         */
        const val HINT_LOG_EVALUATION_CONTEXT = "logEvaluationContext"
    }

    override fun before(ctx: HookContext<T>, hints: Map<String, Any>) {
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

        logger.debug { message }
    }

    override fun after(ctx: HookContext<T>, details: FlagEvaluationDetails<T>, hints: Map<String, Any>) {
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

        logger.debug { message }
    }

    override fun error(ctx: HookContext<T>, error: Exception, hints: Map<String, Any>) {
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

        logger.error(error) { message }
    }

    override fun finallyAfter(ctx: HookContext<T>, details: FlagEvaluationDetails<T>, hints: Map<String, Any>) {
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

        logger.debug { message }
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