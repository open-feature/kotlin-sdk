package dev.openfeature.kotlin.sdk

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
}