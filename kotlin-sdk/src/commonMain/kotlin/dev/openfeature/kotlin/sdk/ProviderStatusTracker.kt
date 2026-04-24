package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.toOpenFeatureStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single place for [OpenFeatureStatus] and the provider event stream: call [send] for each lifecycle
 * step; [status] is derived with the same [toOpenFeatureStatus] rules as the rest of the SDK. Do not
 * set readiness from another [StateFlow] outside [send]. For [StateManagingProvider], expose
 * [status] and [observe] from the tracker, or use [OpenFeatureAPI.statusFlow] for readiness if registered.
 *
 * [observe] uses a [MutableSharedFlow] with `replay = 1`. New subscribers replay the
 * most recent [send] (if any), then all later [send] calls. Use [status] for a readiness snapshot when
 * the replay is not a lifecycle-style event. The event flow does not conflate by [equals] (unlike [status]).
 *
 * [send] is synchronized (atomicfu) to keep [OpenFeatureStatus] updates and emissions ordered; do not
 * call [send] re-entrantly from [observe] collection.
 */
class ProviderStatusTracker {
    private val providerMutex = SynchronizedObject()
    private val _status = MutableStateFlow<OpenFeatureStatus>(OpenFeatureStatus.NotReady)
    private val _events = MutableSharedFlow<OpenFeatureProviderEvents>(replay = 1, extraBufferCapacity = 5)

    val status: StateFlow<OpenFeatureStatus> = _status.asStateFlow()

    fun send(event: OpenFeatureProviderEvents) {
        synchronized(providerMutex) {
            event.toOpenFeatureStatus()?.let { _status.value = it }
            _events.tryEmit(event)
        }
    }

    fun observe(): Flow<OpenFeatureProviderEvents> = _events.asSharedFlow()
}