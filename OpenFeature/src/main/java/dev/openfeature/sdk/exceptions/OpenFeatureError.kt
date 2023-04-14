package dev.openfeature.sdk.exceptions

sealed class OpenFeatureError : Exception() {
    abstract fun errorCode(): ErrorCode

    class GeneralError(override val message: String): OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.GENERAL
        }
    }

    class FlagNotFoundError(flagKey: String): OpenFeatureError() {
        override val message: String = "Could not find flag named: $flagKey"
        override fun errorCode(): ErrorCode {
            return ErrorCode.FLAG_NOT_FOUND
        }
    }

    class InvalidContextError(
        override val message: String = "Invalid context"): OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.INVALID_CONTEXT
        }
    }

    class ParseError(override val message: String): OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.PARSE_ERROR
        }
    }

    class TargetingKeyMissingError(override val message: String = "Targeting key missing in evaluation context"): OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.TARGETING_KEY_MISSING
        }
    }

    class ProviderNotReadyError(override val message: String = "The value was resolved before the provider was ready"): OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.PROVIDER_NOT_READY
        }
    }
}