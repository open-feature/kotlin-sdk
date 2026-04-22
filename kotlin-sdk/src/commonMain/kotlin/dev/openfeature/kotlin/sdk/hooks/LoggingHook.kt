package dev.openfeature.kotlin.sdk.hooks

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.logging.LogLevel
import dev.openfeature.kotlin.sdk.logging.Logger
import dev.openfeature.kotlin.sdk.logging.NoOpLogger

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
 * @param logTargetingKey If true, includes the targeting key when logging context (default: true).
 *                        Set to false if targeting keys contain PII such as user IDs or emails.
 * @param includeAttributes If specified, only these attributes are logged. Takes precedence over excludeAttributes.
 *                          An empty set logs no attributes. Attribute name matching is case-sensitive.
 * @param excludeAttributes Attributes to exclude from logging. Defaults to common PII fields ([DEFAULT_SENSITIVE_KEYS]).
 *                          Attribute name matching is case-sensitive.
 */
class LoggingHook(
    private val logger: Logger = NoOpLogger(),
    private val logEvaluationContext: Boolean = false,
    private val beforeLogLevel: LogLevel = LogLevel.DEBUG,
    private val afterLogLevel: LogLevel = LogLevel.DEBUG,
    private val errorLogLevel: LogLevel = LogLevel.ERROR,
    private val finallyLogLevel: LogLevel = LogLevel.DEBUG,
    private val logTargetingKey: Boolean = true,
    private val includeAttributes: Set<String>? = null,
    private val excludeAttributes: Set<String> = DEFAULT_SENSITIVE_KEYS
) : Hook<Any> {

    companion object {
        /**
         * Hook hint key to enable/disable context logging for a specific evaluation.
         * Pass this key in hookHints with a Boolean value to override the hook's default behavior.
         */
        const val HINT_LOG_EVALUATION_CONTEXT = "logEvaluationContext"

        /**
         * Common attribute names that likely contain PII.
         * These are excluded by default when logEvaluationContext is true.
         */
        val DEFAULT_SENSITIVE_KEYS =
            setOf(
                "email",
                "phone",
                "phoneNumber",
                "ssn",
                "socialSecurityNumber",
                "creditCard",
                "creditCardNumber",
                "password",
                "address",
                "streetAddress",
                "zipCode",
                "postalCode",
                "ipAddress",
                "firstName",
                "lastName",
                "fullName",
                "dateOfBirth"
            )
    }

    override fun before(ctx: HookContext<Any>, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        logAtLevel(
            beforeLogLevel,
            message = { "Flag evaluation starting" },
            attributes = {
                buildMap {
                    put("flag", ctx.flagKey)
                    put("type", ctx.type.toString())
                    put("defaultValue", ctx.defaultValue)
                    put("provider", ctx.providerMetadata.name)
                    ctx.clientMetadata?.name?.let { put("client", it) }
                    if (shouldLogContext && ctx.ctx != null) {
                        putAll(contextAttributes(ctx.ctx))
                    }
                }
            }
        )
    }

    override fun after(ctx: HookContext<Any>, details: FlagEvaluationDetails<Any>, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        logAtLevel(
            afterLogLevel,
            message = { "Flag evaluation completed" },
            attributes = {
                buildMap {
                    put("flag", details.flagKey)
                    put("value", details.value)
                    details.variant?.let { put("variant", it) }
                    details.reason?.let { put("reason", it) }
                    put("provider", ctx.providerMetadata.name)
                    if (shouldLogContext && ctx.ctx != null) {
                        putAll(contextAttributes(ctx.ctx))
                    }
                }
            }
        )
    }

    override fun error(ctx: HookContext<Any>, error: Exception, hints: Map<String, Any>) {
        val shouldLogContext = hints[HINT_LOG_EVALUATION_CONTEXT] as? Boolean ?: logEvaluationContext

        logAtLevel(
            errorLogLevel,
            throwable = error,
            message = { "Flag evaluation error" },
            attributes = {
                buildMap {
                    put("flag", ctx.flagKey)
                    put("type", ctx.type.toString())
                    put("defaultValue", ctx.defaultValue)
                    put("provider", ctx.providerMetadata.name)
                    error.message?.let { put("error", it) }
                    if (shouldLogContext && ctx.ctx != null) {
                        putAll(contextAttributes(ctx.ctx))
                    }
                }
            }
        )
    }

    override fun finallyAfter(ctx: HookContext<Any>, details: FlagEvaluationDetails<Any>, hints: Map<String, Any>) {
        logAtLevel(
            finallyLogLevel,
            message = { "Flag evaluation finalized" },
            attributes = {
                buildMap {
                    put("flag", ctx.flagKey)
                    details.errorCode?.let { put("errorCode", it.toString()) }
                    details.errorMessage?.let { put("errorMessage", it) }
                }
            }
        )
    }

    private fun logAtLevel(
        level: LogLevel,
        throwable: Throwable? = null,
        message: () -> String,
        attributes: () -> Map<String, Any?>
    ) {
        when (level) {
            LogLevel.DEBUG -> logger.debug(message, attributes, throwable)
            LogLevel.INFO -> logger.info(message, attributes, throwable)
            LogLevel.WARN -> logger.warn(message, attributes, throwable)
            LogLevel.ERROR -> logger.error(message, attributes, throwable)
        }
    }

    private fun filterAttributes(attributes: Map<String, Any?>): Map<String, Any?> =
        when {
            includeAttributes != null -> attributes.filterKeys { it in includeAttributes }
            else -> attributes.filterKeys { it !in excludeAttributes }
        }

    private fun contextAttributes(context: EvaluationContext): Map<String, Any?> = buildMap {
        // asObjectMap() unwraps Value subtypes to native Kotlin types (String, Int, Boolean,
        // Instant, List<Any?>, Map<String, Any?>) — reuses the SDK's own conversion logic.
        // ImmutableContext stores the targeting key separately from its attributes map, so
        // asObjectMap() will not include it for the standard implementation.
        filterAttributes(context.asObjectMap()).forEach { (key, value) ->
            put("context.$key", value)
        }
        // getTargetingKey() returns "" when not set (non-nullable), so check isNotEmpty.
        // Put after asObjectMap() so that targeting key always wins if a custom
        // EvaluationContext also returns it from asObjectMap().
        if (logTargetingKey) {
            context.getTargetingKey().takeIf { it.isNotEmpty() }?.let { put("context.targetingKey", it) }
        }
    }
}