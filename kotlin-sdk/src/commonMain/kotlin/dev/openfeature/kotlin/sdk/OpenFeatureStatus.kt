package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

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
     *
     * Compared by the failure it describes, so the same failure reported twice is one status.
     */
    class Error(val error: OpenFeatureError) : OpenFeatureStatus {
        override fun equals(other: Any?): Boolean = other is Error && describesSameError(error, other.error)

        override fun hashCode(): Int = errorHashCode(error)
    }

    /**
     * The provider has entered an irrecoverable error state.
     */
    class Fatal(val error: OpenFeatureError) : OpenFeatureStatus {
        override fun equals(other: Any?): Boolean = other is Fatal && describesSameError(error, other.error)

        override fun hashCode(): Int = errorHashCode(error)
    }

    /**
     * The provider's cached state is no longer valid and may not be up-to-date with the source of truth.
     */
    object Stale : OpenFeatureStatus

    /**
     * The provider is reconciling its state with a context change.
     */
    object Reconciling : OpenFeatureStatus
}

private fun describesSameError(left: OpenFeatureError, right: OpenFeatureError): Boolean =
    left.errorCode() == right.errorCode() && left.message == right.message

private fun errorHashCode(error: OpenFeatureError): Int =
    31 * error.errorCode().hashCode() + error.message.hashCode()