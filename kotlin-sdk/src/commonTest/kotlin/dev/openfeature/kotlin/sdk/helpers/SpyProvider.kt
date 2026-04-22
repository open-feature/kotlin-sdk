package dev.openfeature.kotlin.sdk.helpers

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.StateManagingProvider
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpyProvider(
    override val hooks: List<Hook<*>> = listOf(),
    override val metadata: ProviderMetadata = SpyProviderMetadata("spy")
) : StateManagingProvider {

    val initializeCalls = mutableListOf<EvaluationContext?>()
    val onContextSetCalls = mutableListOf<Pair<EvaluationContext?, EvaluationContext>>()
    val shutdownCalls = atomic(0)

    private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
    override val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    override suspend fun initialize(initialContext: EvaluationContext?) {
        initializeCalls.add(initialContext)
        _status.value = OpenFeatureStatus.Ready
        events.emit(OpenFeatureProviderEvents.ProviderReady())
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
        _status.value = OpenFeatureStatus.NotReady
        events.tryEmit(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "Spy provider shut down; not ready for evaluation",
                    errorCode = ErrorCode.PROVIDER_NOT_READY
                )
            )
        )
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext
    ) {
        onContextSetCalls.add(Pair(oldContext, newContext))
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?
    ): ProviderEvaluation<Boolean> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?
    ): ProviderEvaluation<String> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?
    ): ProviderEvaluation<Int> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?
    ): ProviderEvaluation<Long> {
        TODO("Not yet implemented")
    }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?
    ): ProviderEvaluation<Double> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?
    ): ProviderEvaluation<Value> =
        ProviderEvaluation(defaultValue, "Passed in default", Reason.DEFAULT.toString())

    data class SpyProviderMetadata(override val name: String?) : ProviderMetadata
}