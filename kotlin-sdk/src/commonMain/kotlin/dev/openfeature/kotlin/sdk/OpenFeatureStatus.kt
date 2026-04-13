package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

sealed interface OpenFeatureStatus {
    /**
     * The provider has not been initialized and cannot yet evaluate flags.
     */
    object NotReady : OpenFeatureStatus

    /**
     * Initialization finished for a passive placeholder (e.g. the built-in no-op provider): not
     * [NotReady], so [OpenFeatureAPI.setProviderAndWait] can complete, but flag evaluations are still
     * blocked the same way as [NotReady] until a full provider reaches [Ready].
     */
    object Inactive : OpenFeatureStatus

    /**
     * The provider is ready to resolve flags.
     */
    object Ready : OpenFeatureStatus

    /**
     * The provider is in an error state and unable to evaluate flags.
     */
    class Error(val error: OpenFeatureError) : OpenFeatureStatus

    /**
     * The provider has entered an irrecoverable error state.
     */
    class Fatal(val error: OpenFeatureError) : OpenFeatureStatus

    /**
     * The provider's cached state is no longer valid and may not be up-to-date with the source of truth.
     */
    object Stale : OpenFeatureStatus

    /**
     * The provider is reconciling its state with a context change.
     */
    object Reconciling : OpenFeatureStatus

    /**
     * The provider's configuration has changed.
     */
    object ConfigurationChanged : OpenFeatureStatus
}