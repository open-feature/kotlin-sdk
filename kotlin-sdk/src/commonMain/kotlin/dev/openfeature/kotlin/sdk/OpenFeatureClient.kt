package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.FlagValueType.BOOLEAN
import dev.openfeature.kotlin.sdk.FlagValueType.DOUBLE
import dev.openfeature.kotlin.sdk.FlagValueType.INTEGER
import dev.openfeature.kotlin.sdk.FlagValueType.OBJECT
import dev.openfeature.kotlin.sdk.FlagValueType.STRING
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.GeneralError

private val typeMatchingException =
    GeneralError("Unable to match default value type with flag value type")

class OpenFeatureClient(
    private val openFeatureAPI: OpenFeatureAPI,
    name: String? = null,
    version: String? = null,
    override val hooks: MutableList<Hook<*>> = mutableListOf()
) : Client {
    override val metadata: ClientMetadata = Metadata(name)
    private val hookSupport = HookSupport()
    override fun addHooks(hooks: List<Hook<*>>) {
        this.hooks += hooks
    }

    override val statusFlow = openFeatureAPI.statusFlow

    override fun getBooleanValue(key: String, defaultValue: Boolean): Boolean =
        getBooleanDetails(key, defaultValue).value

    override fun getBooleanValue(key: String, defaultValue: Boolean, options: FlagEvaluationOptions): Boolean =
        getBooleanDetails(key, defaultValue, options).value

    override fun getBooleanValue(
        key: String,
        defaultValue: Boolean,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): Boolean = getBooleanDetails(key, defaultValue, options, evaluationContext).value

    override fun getBooleanDetails(key: String, defaultValue: Boolean): FlagEvaluationDetails<Boolean> =
        evaluateFlag(BOOLEAN, key, defaultValue, null, FlagEvaluationOptions())

    override fun getBooleanDetails(
        key: String,
        defaultValue: Boolean,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Boolean> = evaluateFlag(BOOLEAN, key, defaultValue, null, options)

    override fun getBooleanDetails(
        key: String,
        defaultValue: Boolean,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): FlagEvaluationDetails<Boolean> = evaluateFlag(BOOLEAN, key, defaultValue, evaluationContext, options)

    override fun getStringValue(key: String, defaultValue: String): String =
        getStringDetails(key, defaultValue).value

    override fun getStringValue(key: String, defaultValue: String, options: FlagEvaluationOptions): String =
        getStringDetails(key, defaultValue, options).value

    override fun getStringValue(
        key: String,
        defaultValue: String,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): String = getStringDetails(key, defaultValue, options, evaluationContext).value

    override fun getStringDetails(key: String, defaultValue: String): FlagEvaluationDetails<String> =
        evaluateFlag(STRING, key, defaultValue, null, FlagEvaluationOptions())

    override fun getStringDetails(
        key: String,
        defaultValue: String,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<String> = evaluateFlag(STRING, key, defaultValue, null, options)

    override fun getStringDetails(
        key: String,
        defaultValue: String,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): FlagEvaluationDetails<String> = evaluateFlag(STRING, key, defaultValue, evaluationContext, options)

    override fun getIntegerValue(key: String, defaultValue: Int): Int =
        getIntegerDetails(key, defaultValue).value

    override fun getIntegerValue(key: String, defaultValue: Int, options: FlagEvaluationOptions): Int =
        getIntegerDetails(key, defaultValue, options).value

    override fun getIntegerValue(
        key: String,
        defaultValue: Int,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): Int = getIntegerDetails(key, defaultValue, options, evaluationContext).value

    override fun getIntegerDetails(key: String, defaultValue: Int): FlagEvaluationDetails<Int> =
        evaluateFlag(INTEGER, key, defaultValue, null, FlagEvaluationOptions())

    override fun getIntegerDetails(
        key: String,
        defaultValue: Int,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Int> = evaluateFlag(INTEGER, key, defaultValue, null, options)

    override fun getIntegerDetails(
        key: String,
        defaultValue: Int,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): FlagEvaluationDetails<Int> = evaluateFlag(INTEGER, key, defaultValue, evaluationContext, options)

    override fun getDoubleValue(key: String, defaultValue: Double): Double =
        getDoubleDetails(key, defaultValue).value

    override fun getDoubleValue(key: String, defaultValue: Double, options: FlagEvaluationOptions): Double =
        getDoubleDetails(key, defaultValue, options).value

    override fun getDoubleValue(
        key: String,
        defaultValue: Double,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): Double = getDoubleDetails(key, defaultValue, options, evaluationContext).value

    override fun getDoubleDetails(key: String, defaultValue: Double): FlagEvaluationDetails<Double> =
        evaluateFlag(DOUBLE, key, defaultValue, null, FlagEvaluationOptions())

    override fun getDoubleDetails(
        key: String,
        defaultValue: Double,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Double> = evaluateFlag(DOUBLE, key, defaultValue, null, options)

    override fun getDoubleDetails(
        key: String,
        defaultValue: Double,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): FlagEvaluationDetails<Double> = evaluateFlag(DOUBLE, key, defaultValue, evaluationContext, options)

    override fun getObjectValue(key: String, defaultValue: Value): Value =
        getObjectDetails(key, defaultValue).value

    override fun getObjectValue(key: String, defaultValue: Value, options: FlagEvaluationOptions): Value =
        getObjectDetails(key, defaultValue, options).value

    override fun getObjectValue(
        key: String,
        defaultValue: Value,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): Value = getObjectDetails(key, defaultValue, options, evaluationContext).value

    override fun getObjectDetails(key: String, defaultValue: Value): FlagEvaluationDetails<Value> =
        evaluateFlag(OBJECT, key, defaultValue, null, FlagEvaluationOptions())

    override fun getObjectDetails(
        key: String,
        defaultValue: Value,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Value> = evaluateFlag(OBJECT, key, defaultValue, null, options)

    override fun getObjectDetails(
        key: String,
        defaultValue: Value,
        options: FlagEvaluationOptions,
        evaluationContext: EvaluationContext?,
    ): FlagEvaluationDetails<Value> = evaluateFlag(OBJECT, key, defaultValue, evaluationContext, options)

    override fun track(trackingEventName: String, details: TrackingEventDetails?) {
        validateTrackingEventName(trackingEventName)
        openFeatureAPI.getProvider()
            .track(trackingEventName, openFeatureAPI.getEvaluationContext(), details)
    }

    private fun <T> evaluateFlag(
        flagValueType: FlagValueType,
        key: String,
        defaultValue: T,
        invocationContext: EvaluationContext?,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<T> {
        val hints = options.hookHints
        var details = FlagEvaluationDetails(key, defaultValue)
        val provider = openFeatureAPI.getProvider()
        val mergedHooks: List<Hook<*>> = provider.hooks + options.hooks + hooks + openFeatureAPI.hooks
        val globalContext = openFeatureAPI.getEvaluationContext()
        val context = invocationContext?.let { mergeEvaluationContexts(globalContext, it) } ?: globalContext
        val hookCtx: HookContext<T> = HookContext(
            key,
            flagValueType,
            defaultValue,
            context,
            this.metadata,
            provider.metadata
        )
        try {
            hookSupport.beforeHooks(flagValueType, hookCtx, mergedHooks, hints)
            shortCircuitIfNotReady()
            val providerEval = createProviderEvaluation(
                flagValueType,
                key,
                context,
                defaultValue,
                provider
            )
            details = FlagEvaluationDetails.from(providerEval, key)
            hookSupport.afterHooks(flagValueType, hookCtx, details, mergedHooks, hints)
        } catch (error: Exception) {
            val errorCode = if (error is OpenFeatureError) {
                error.errorCode()
            } else {
                ErrorCode.GENERAL
            }

            details = details.copy(
                errorMessage = error.message,
                reason = Reason.ERROR.toString(),
                errorCode = errorCode
            )

            hookSupport.errorHooks(flagValueType, hookCtx, error, mergedHooks, hints)
        }
        hookSupport.afterAllHooks(flagValueType, hookCtx, details, mergedHooks, hints)
        return details
    }

    private fun shortCircuitIfNotReady() {
        val providerStatus = openFeatureAPI.getStatus()
        if (providerStatus == OpenFeatureStatus.NotReady) {
            throw OpenFeatureError.ProviderNotReadyError()
        } else if (providerStatus is OpenFeatureStatus.Fatal) {
            throw OpenFeatureError.ProviderFatalError()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> createProviderEvaluation(
        flagValueType: FlagValueType,
        key: String,
        context: EvaluationContext?,
        defaultValue: V,
        provider: FeatureProvider
    ): ProviderEvaluation<V> {
        return when (flagValueType) {
            BOOLEAN -> {
                val defaultBoolean = defaultValue as? Boolean ?: throw typeMatchingException
                val eval: ProviderEvaluation<Boolean> =
                    provider.getBooleanEvaluation(key, defaultBoolean, context)
                eval as? ProviderEvaluation<V> ?: throw typeMatchingException
            }

            STRING -> {
                val defaultString = defaultValue as? String ?: throw typeMatchingException
                val eval: ProviderEvaluation<String> =
                    provider.getStringEvaluation(key, defaultString, context)
                eval as? ProviderEvaluation<V> ?: throw typeMatchingException
            }

            INTEGER -> {
                val defaultInteger = defaultValue as? Int ?: throw typeMatchingException
                val eval: ProviderEvaluation<Int> =
                    provider.getIntegerEvaluation(key, defaultInteger, context)
                eval as? ProviderEvaluation<V> ?: throw typeMatchingException
            }

            DOUBLE -> {
                val defaultDouble = defaultValue as? Double ?: throw typeMatchingException
                val eval: ProviderEvaluation<Double> =
                    provider.getDoubleEvaluation(key, defaultDouble, context)
                eval as? ProviderEvaluation<V> ?: throw typeMatchingException
            }

            OBJECT -> {
                val defaultObject = defaultValue as? Value ?: throw typeMatchingException
                val eval: ProviderEvaluation<Value> =
                    provider.getObjectEvaluation(key, defaultObject, context)
                eval as? ProviderEvaluation<V> ?: throw typeMatchingException
            }
        }
    }

    data class Metadata(override val name: String?) : ClientMetadata
}

private fun validateTrackingEventName(name: String) {
    if (name.isEmpty()) {
        throw IllegalArgumentException("trackingEventName cannot be empty")
    }
}

/**
 * Merges [base] with [overlay] without mutating either. [overlay] wins on attribute key collision.
 * Non-empty [overlay] targeting key overrides; empty overlay targeting inherits [base].
 */
internal fun mergeEvaluationContexts(base: EvaluationContext?, overlay: EvaluationContext): EvaluationContext {
    val mergedAttributes = base?.asMap().orEmpty() + overlay.asMap()
    val mergedTargeting = when {
        overlay.getTargetingKey().isNotEmpty() -> overlay.getTargetingKey()
        base != null -> base.getTargetingKey()
        else -> ""
    }
    return ImmutableContext(targetingKey = mergedTargeting, attributes = mergedAttributes)
}