package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages its own OpenFeatureStatus.
 *
 * The SDK reads status directly from `status`. The implementation must update it during
 * lifecycle events such as initialize, onContextSet, and shutdown.
 *
 * Implementations that don’t follow this use the deprecated shared Provider/SDK-managed
 * status system.
 */
interface StateManagingProvider : FeatureProvider {
    val status: StateFlow<OpenFeatureStatus>

    /**
     * Lifecycle events for this provider. Implementations must define this explicitly so
     * callers do not silently inherit [FeatureProvider.observe]'s empty default, and so
     * emissions stay consistent with how [status] is updated.
     */
    override fun observe(): Flow<OpenFeatureProviderEvents>
}