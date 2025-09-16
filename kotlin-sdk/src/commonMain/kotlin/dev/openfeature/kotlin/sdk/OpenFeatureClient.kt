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

    override fun getBooleanValue(key: String, defaultValue: Boolean): Boolean {
        return getBooleanDetails(key, defaultValue).value
    }

    override fun getBooleanValue(
        key: String,
        defaultValue: Boolean,
        options: FlagEvaluationOptions
    ): Boolean {
        return getBooleanDetails(key, defaultValue, options).value
    }

    override fun getBooleanDetails(
        key: String,
        defaultValue: Boolean
    ): FlagEvaluationDetails<Boolean> {
        return getBooleanDetails(key, defaultValue, FlagEvaluationOptions())
    }

    override fun getBooleanDetails(
        key: String,
        defaultValue: Boolean,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Boolean> {
        return evaluateFlag(BOOLEAN, key, defaultValue, options)
    }

    override fun getStringValue(key: String, defaultValue: String): String {
        return getStringDetails(key, defaultValue).value
    }

    override fun getStringValue(
        key: String,
        defaultValue: String,
        options: FlagEvaluationOptions
    ): String {
        return getStringDetails(key, defaultValue, options).value
    }

    override fun getStringDetails(
        key: String,
        defaultValue: String
    ): FlagEvaluationDetails<String> {
        return getStringDetails(key, defaultValue, FlagEvaluationOptions())
    }

    override fun getStringDetails(
        key: String,
        defaultValue: String,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<String> {
        return evaluateFlag(STRING, key, defaultValue, options)
    }

    override fun getIntegerValue(key: String, defaultValue: Int): Int {
        return getIntegerDetails(key, defaultValue).value
    }

    override fun getIntegerValue(
        key: String,
        defaultValue: Int,
        options: FlagEvaluationOptions
    ): Int {
        return getIntegerDetails(key, defaultValue, options).value
    }

    override fun getIntegerDetails(
        key: String,
        defaultValue: Int
    ): FlagEvaluationDetails<Int> {
        return getIntegerDetails(key, defaultValue, FlagEvaluationOptions())
    }

    override fun getIntegerDetails(
        key: String,
        defaultValue: Int,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Int> {
        return evaluateFlag(INTEGER, key, defaultValue, options)
    }

    override fun getDoubleValue(key: String, defaultValue: Double): Double {
        return getDoubleDetails(key, defaultValue).value
    }

    override fun getDoubleValue(
        key: String,
        defaultValue: Double,
        options: FlagEvaluationOptions
    ): Double {
        return getDoubleDetails(key, defaultValue, options).value
    }

    override fun getDoubleDetails(
        key: String,
        defaultValue: Double
    ): FlagEvaluationDetails<Double> {
        return evaluateFlag(DOUBLE, key, defaultValue, FlagEvaluationOptions())
    }

    override fun getDoubleDetails(
        key: String,
        defaultValue: Double,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Double> {
        return evaluateFlag(DOUBLE, key, defaultValue, options)
    }

    override fun getObjectValue(key: String, defaultValue: Value): Value {
        return getObjectDetails(key, defaultValue).value
    }

    override fun getObjectValue(
        key: String,
        defaultValue: Value,
        options: FlagEvaluationOptions
    ): Value {
        return getObjectDetails(key, defaultValue, options).value
    }

    override fun getObjectDetails(
        key: String,
        defaultValue: Value
    ): FlagEvaluationDetails<Value> {
        return getObjectDetails(key, defaultValue, FlagEvaluationOptions())
    }

    override fun getObjectDetails(
        key: String,
        defaultValue: Value,
        options: FlagEvaluationOptions
    ): FlagEvaluationDetails<Value> {
        return evaluateFlag(OBJECT, key, defaultValue, options)
    }

    override fun track(trackingEventName: String, details: TrackingEventDetails?) {
        validateTrackingEventName(trackingEventName)
        openFeatureAPI.getProvider()
            .track(trackingEventName, openFeatureAPI.getEvaluationContext(), details)
    }

    private fun <T> evaluateFlag(
        flagValueType: FlagValueType,
        key: String,
        defaultValue: T,
        optionsIn: FlagEvaluationOptions?
    ): FlagEvaluationDetails<T> {
        val options = optionsIn ?: FlagEvaluationOptions(listOf(), mapOf())
        val hints = options.hookHints
        var details = FlagEvaluationDetails(key, defaultValue)
        val provider = openFeatureAPI.getProvider()
        val mergedHooks: List<Hook<*>> = provider.hooks + options.hooks + hooks + openFeatureAPI.hooks
        val context = openFeatureAPI.getEvaluationContext()
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