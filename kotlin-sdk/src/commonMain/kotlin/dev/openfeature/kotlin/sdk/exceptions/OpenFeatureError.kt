package dev.openfeature.kotlin.sdk.exceptions

sealed class OpenFeatureError : Exception() {
    abstract fun errorCode(): ErrorCode

    class GeneralError(override val message: String) : OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.GENERAL
        }
    }

    class FlagNotFoundError(
        flagKey: String?,
        override val message: String = "Could not find flag named: $flagKey"
    ) : OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.FLAG_NOT_FOUND
        }
    }

    class InvalidContextError(
        override val message: String = "Invalid or missing context"
    ) : OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.INVALID_CONTEXT
        }
    }

    class ParseError(override val message: String) : OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.PARSE_ERROR
        }
    }

    class TargetingKeyMissingError(override val message: String = "Targeting key missing in evaluation context") :
        OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.TARGETING_KEY_MISSING
        }
    }

    class ProviderNotReadyError(override val message: String = "The value was resolved before the provider was ready") :
        OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.PROVIDER_NOT_READY
        }
    }

    class TypeMismatchError(override val message: String = "The value doesn't match the expected type") :
        OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.TYPE_MISMATCH
        }
    }

    class ProviderFatalError(override val message: String = "The Provider is in an irrecoverable error state") :
        OpenFeatureError() {
        override fun errorCode(): ErrorCode {
            return ErrorCode.PROVIDER_FATAL
        }
    }

    companion object {
        internal fun fromMessageAndErrorCode(errorMessage: String, errorCode: ErrorCode): OpenFeatureError {
            return when (errorCode) {
                ErrorCode.PROVIDER_NOT_READY -> ProviderNotReadyError()
                ErrorCode.FLAG_NOT_FOUND -> FlagNotFoundError(flagKey = null, errorMessage)
                ErrorCode.PARSE_ERROR -> ParseError(errorMessage)
                ErrorCode.TYPE_MISMATCH -> TypeMismatchError(errorMessage)
                ErrorCode.TARGETING_KEY_MISSING -> TargetingKeyMissingError(errorMessage)
                ErrorCode.INVALID_CONTEXT -> InvalidContextError(errorMessage)
                ErrorCode.GENERAL -> GeneralError(errorMessage)
                ErrorCode.PROVIDER_FATAL -> ProviderFatalError(errorMessage)
            }
        }
    }
}