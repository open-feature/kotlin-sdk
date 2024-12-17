package dev.openfeature.sdk

data class TrackingEventDetails(
    val `value`: Number? = null,
    val structure: Structure = ImmutableStructure()
) : Structure by structure