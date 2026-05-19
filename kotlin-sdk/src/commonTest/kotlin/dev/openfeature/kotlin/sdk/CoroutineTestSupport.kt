package dev.openfeature.kotlin.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * [OpenFeatureAPI] shares provider events on [Dispatchers.Default]; [runTest] does not advance that dispatcher.
 */
internal suspend fun flushDispatchersDefault() {
    withContext(Dispatchers.Default) {
        delay(1)
    }
}