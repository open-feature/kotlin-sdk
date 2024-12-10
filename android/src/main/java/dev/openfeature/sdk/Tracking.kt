package dev.openfeature.sdk

/**
 * Interface for Tracking events.
 */
interface Tracking {
    /**
     * Performs tracking of a particular action or application state.
     *
     * @param trackingEventName Event name to track
     * @param details           Data pertinent to a particular tracking event
     * @throws IllegalArgumentException if {@code trackingEventName} is null
     */
    fun track(trackingEventName: String, details: TrackingEventDetails? = null)
}