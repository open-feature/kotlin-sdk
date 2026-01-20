package dev.openfeature.kotlin.sdk.hooks

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.logging.Logger
import dev.openfeature.kotlin.sdk.logging.NoOpLogger

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
        private const val HINT_LOG_EVALUATION_CONTEXT = "logEvaluationContext"
    }

    override fun before(ctx: HookContext<T>, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        val message = buildString {
            append("Flag evaluation starting: ")
            append("flag='${ctx.flagKey}', ")
            append("type=${ctx.type}, ")
            append("defaultValue=${ctx.defaultValue}")
            if (shouldLogContext && ctx.ctx != null) {
                append(", ")
                append(formatContext(ctx.ctx))
            }
            append(", provider='${ctx.providerMetadata.name}'")
            if (ctx.clientMetadata?.name != null) {
                append(", client='${ctx.clientMetadata.name}'")
            }
        }

        logger.debug(message)
    }

    override fun after(ctx: HookContext<T>, details: FlagEvaluationDetails<T>, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        val message = buildString {
            append("Flag evaluation completed: ")
            append("flag='${details.flagKey}', ")
            append("value=${details.value}")
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

        logger.debug(message)
    }

    override fun error(ctx: HookContext<T>, error: Exception, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        val message = buildString {
            append("Flag evaluation error: ")
            append("flag='${ctx.flagKey}', ")
            append("type=${ctx.type}, ")
            append("defaultValue=${ctx.defaultValue}")
            if (shouldLogContext && ctx.ctx != null) {
                append(", ")
                append(formatContext(ctx.ctx))
            }
            append(", provider='${ctx.providerMetadata.name}', ")
            append("error='${error.message}'")
        }

        logger.error(message, error)
    }

    override fun finallyAfter(ctx: HookContext<T>, details: FlagEvaluationDetails<T>, hints: Map<String, Any>) {
        val message = buildString {
            append("Flag evaluation finalized: ")
            append("flag='${ctx.flagKey}'")
            if (details.errorCode != null) {
                append(", errorCode=${details.errorCode}")
            }
            if (details.errorMessage != null) {
                append(", errorMessage='${details.errorMessage}'")
            }
        }

        logger.debug(message)
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
            is Value.String -> value.asString() ?: "null"
            is Value.Integer -> value.asInteger()?.toString() ?: "null"
            is Value.Double -> value.asDouble()?.toString() ?: "null"
            is Value.Boolean -> value.asBoolean()?.toString() ?: "null"
            is Value.List -> "[${value.asList()?.joinToString(", ") { formatValue(it) } ?: ""}]"
            is Value.Structure -> "{${value.asStructure()?.entries?.joinToString(", ") { "${it.key}=${formatValue(it.value)}" } ?: ""}}"
            is Value.Null -> "null"
            else -> value.toString()
        }
    }
}
