package dev.openfeature.sdk

import dev.openfeature.sdk.exceptions.OpenFeatureError

sealed interface OpenFeatureStatus {
    /**
     * The provider has not been initialized and cannot yet evaluate flags.
     */
    object NotReady : OpenFeatureStatus

    /**
     * The provider is ready to resolve flags.
     */
    object Ready : OpenFeatureStatus

    /**
     * The provider is in an error state and unable to evaluate flags.
     */
    class Error(val error: OpenFeatureError) : OpenFeatureStatus

    /**
     * The provider's cached state is no longer valid and may not be up-to-date with the source of truth.
     */
    object Stale : OpenFeatureStatus

    /**
     * The provider has entered an irrecoverable error state.
     */
    object Fatal : OpenFeatureStatus

    /**
     * The provider is reconciling its state with a context change.
     */
    object Reconciling : OpenFeatureStatus
}