package dev.openfeature.kotlin.sdk.providers.memory

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.EvaluationMetadata
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.EventDetails
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.ProviderConfigurationChanged
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.ProviderReady
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.FlagNotFoundError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.ProviderNotReadyError
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.TypeMismatchError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

class InMemoryProvider(initialFlags: Map<String, Flag<*>> = emptyMap()) : FeatureProvider {

    override val hooks: List<Hook<*>> = emptyList()
    override val metadata: ProviderMetadata = InMemoryProviderMetadata("InMemoryProvider")

    private val flagsState = MutableStateFlow<Map<String, Flag<*>>>(initialFlags.toMap())

    @Volatile
    private var state: OpenFeatureStatus = OpenFeatureStatus.NotReady

    private val eventFlow = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = 64)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        state = OpenFeatureStatus.Ready
        eventFlow.tryEmit(ProviderReady())
    }

    override fun shutdown() {
        state = OpenFeatureStatus.NotReady
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        // no-op for in-memory provider as flags aren't context-bound on fetch
    }

    fun updateFlags(newFlags: Map<String, Flag<*>>) {
        val flagsChanged = newFlags.keys.toSet()
        flagsState.update { currentFlags ->
            currentFlags + newFlags
        }
        eventFlow.tryEmit(
            ProviderConfigurationChanged(
                EventDetails(flagsChanged = flagsChanged, message = "flags changed")
            )
        )
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = eventFlow.asSharedFlow()

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> {
        return getEvaluation(key, defaultValue, context, Boolean::class)
    }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> {
        return getEvaluation(key, defaultValue, context, String::class)
    }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> {
        return getEvaluation(key, defaultValue, context, Int::class)
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> {
        return getEvaluation(key, defaultValue, context, Double::class)
    }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> {
        return getEvaluation(key, defaultValue, context, Value::class)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getEvaluation(
        key: String,
        defaultValue: T,
        context: EvaluationContext?,
        expectedClass: kotlin.reflect.KClass<T>
    ): ProviderEvaluation<T> {
        when (val currentState = state) {
            is OpenFeatureStatus.NotReady -> throw ProviderNotReadyError("provider not yet initialized")
            is OpenFeatureStatus.Fatal -> throw currentState.error
            is OpenFeatureStatus.Error -> throw currentState.error
            else -> {
                // fall through
            }
        }

        val flag = flagsState.value[key] ?: throw FlagNotFoundError(key)

        if (flag.disabled) {
            return ProviderEvaluation(
                value = defaultValue,
                reason = Reason.DISABLED.toString(),
                metadata = flag.flagMetadata
                    ?: EvaluationMetadata.EMPTY
            )
        }

        var value: Any? = null
        var reason = Reason.STATIC
        var errorCode: ErrorCode? = null
        var errorMessage: String? = null
        var variant: String? = flag.defaultVariant

        if (flag.contextEvaluator != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                val evaluatedVariant =
                    (flag.contextEvaluator as ContextEvaluator<T>).evaluate(
                        flag as Flag<T>,
                        context
                    )
                if (evaluatedVariant != null) {
                    if (flag.variants.containsKey(evaluatedVariant)) {
                        value = flag.variants[evaluatedVariant]
                        variant = evaluatedVariant
                        reason = Reason.TARGETING_MATCH
                    } else {
                        errorCode = ErrorCode.GENERAL
                        errorMessage = "Evaluated variant '$evaluatedVariant' not found in variants"
                    }
                }
            } catch (e: Exception) {
                errorCode = ErrorCode.GENERAL
                errorMessage = e.message ?: "Error evaluating context"
            }
            if (value == null) {
                value = flag.defaultVariant?.let { flag.variants[it] }
                variant = flag.defaultVariant
                reason = if (errorCode != null) Reason.ERROR else Reason.DEFAULT
            }
        } else {
            value = flag.defaultVariant?.let { flag.variants[it] }
        }

        if (value != null && !expectedClass.isInstance(value)) {
            throw TypeMismatchError("flag $key evaluated to a type that does not match expected type")
        }

        if (value == null && errorMessage != null) {
            // Provide the caller with the actual failure reason
            value = defaultValue
        } else if (value == null) {
            // Check if expected class is actually instantiated by default, if we reach here there's
            // a problem
            throw TypeMismatchError("flag $key value could not be resolved or cast")
        }

        @Suppress("UNCHECKED_CAST")
        return ProviderEvaluation(
            value = value as T,
            variant = variant,
            reason = reason.toString(),
            errorCode = errorCode,
            errorMessage = errorMessage,
            metadata = flag.flagMetadata ?: EvaluationMetadata.EMPTY
        )
    }

    class InMemoryProviderMetadata(override val name: String) : ProviderMetadata
}