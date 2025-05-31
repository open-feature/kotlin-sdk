package dev.openfeature.kotlin.sdk

data class TrackingEventDetails(
    val `value`: Number? = null,
    val structure: Structure = ImmutableStructure()
) : Structure by structure