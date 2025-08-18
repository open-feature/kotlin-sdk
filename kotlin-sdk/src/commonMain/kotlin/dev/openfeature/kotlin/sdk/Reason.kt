package dev.openfeature.kotlin.sdk

enum class Reason {
    // The resolved value is static (no dynamic evaluation).
    STATIC,

    // The resolved value fell back to a pre-configured value (no dynamic evaluation occurred or dynamic evaluation yielded no result).
    DEFAULT,

    // The resolved value was the result of a dynamic evaluation, such as a rule or specific user-targeting.
    TARGETING_MATCH,

    // The resolved value was the result of pseudorandom assignment.
    SPLIT,

    // The resolved value was retrieved from cache.
    CACHED,

    // / The resolved value was the result of the flag being disabled in the management system.
    DISABLED,

    // / The reason for the resolved value could not be determined.
    UNKNOWN,

    // / The resolved value is non-authoritative or possible out of date
    STALE,

    // / The resolved value was the result of an error.
    ERROR
}