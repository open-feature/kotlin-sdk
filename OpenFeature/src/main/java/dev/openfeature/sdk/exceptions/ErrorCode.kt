package dev.openfeature.sdk.exceptions

enum class ErrorCode {
    // The value was resolved before the provider was ready.
    PROVIDER_NOT_READY,
    // The flag could not be found.
    FLAG_NOT_FOUND,
    // An error was encountered parsing data, such as a flag configuration.
    PARSE_ERROR,
    // The type of the flag value does not match the expected type.
    TYPE_MISMATCH,
    // The provider requires a targeting key and one was not provided in the evaluation context.
    TARGETING_KEY_MISSING,
    // The evaluation context does not meet provider requirements.
    INVALID_CONTEXT,
    // The error was for a reason not enumerated above.
    GENERAL
}
