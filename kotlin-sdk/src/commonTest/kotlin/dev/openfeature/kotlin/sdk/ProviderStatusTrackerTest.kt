package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderStatusTrackerTest {

    @Test
    fun send_updatesStatusFromEventMapping() = runTest {
        val t = ProviderStatusTracker()
        assertEquals(OpenFeatureStatus.NotReady, t.status.value)
        t.send(OpenFeatureProviderEvents.ProviderReady())
        assertEquals(OpenFeatureStatus.Ready, t.status.value)
        t.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "x",
                    errorCode = ErrorCode.GENERAL
                )
            )
        )
        val err = t.status.value
        assertIs<OpenFeatureStatus.Error>(err)
        assertEquals(ErrorCode.GENERAL, err.error.errorCode())
    }

    @Test
    fun observe_replaysLastEventThenSubsequentSends() = runTest {
        val t = ProviderStatusTracker()
        t.send(OpenFeatureProviderEvents.ProviderReady())
        val col = async(start = CoroutineStart.UNDISPATCHED) { t.observe().take(3).toList() }
        t.send(OpenFeatureProviderEvents.ProviderStale())
        t.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        val list = col.await()
        assertIs<OpenFeatureProviderEvents.ProviderReady>(list[0])
        assertIs<OpenFeatureProviderEvents.ProviderStale>(list[1])
        assertIs<OpenFeatureProviderEvents.ProviderConfigurationChanged>(list[2])
    }

    @Test
    fun observe_whenNotReady_noSend_timesOut() = runTest {
        val t = ProviderStatusTracker()
        val got = withTimeoutOrNull(100L) { t.observe().first() }
        assertNull(got)
    }

    @Test
    fun observe_whenNotReadyBlocksUntilSend_firstIsSend() = runTest {
        val t = ProviderStatusTracker()
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(500L) { t.observe().first() }
        }
        t.send(OpenFeatureProviderEvents.ProviderReady())
        assertIs<OpenFeatureProviderEvents.ProviderReady>(firstEvent.await())
    }

    @Test
    fun observe_replay_preservesErrorEventDetails() = runTest {
        val t1 = ProviderStatusTracker()
        t1.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "e",
                    errorCode = ErrorCode.GENERAL
                )
            )
        )
        val s1 = t1.observe().first()
        assertIs<OpenFeatureProviderEvents.ProviderError>(s1)
        assertEquals("e", s1.eventDetails?.message)

        val t2 = ProviderStatusTracker()
        t2.send(
            OpenFeatureProviderEvents.ProviderError(
                OpenFeatureProviderEvents.EventDetails(
                    message = "f",
                    errorCode = ErrorCode.PROVIDER_FATAL
                )
            )
        )
        val s2 = t2.observe().first()
        assertIs<OpenFeatureProviderEvents.ProviderError>(s2)
        assertEquals(ErrorCode.PROVIDER_FATAL, s2.eventDetails?.errorCode)
    }

    @Test
    fun configurationChanged_doesNotChangeStatus_broadcasts() = runTest {
        val t = ProviderStatusTracker()
        t.send(OpenFeatureProviderEvents.ProviderReady())
        val col = async(start = CoroutineStart.UNDISPATCHED) { t.observe().take(2).toList() }
        t.send(OpenFeatureProviderEvents.ProviderConfigurationChanged())
        val list = col.await()
        assertIs<OpenFeatureProviderEvents.ProviderReady>(list[0])
        assertIs<OpenFeatureProviderEvents.ProviderConfigurationChanged>(list[1])
        assertEquals(OpenFeatureStatus.Ready, t.status.value)
    }

    @Test
    fun twoCollectors_bothReceiveSubsequentSends() = runTest {
        val t = ProviderStatusTracker()
        t.send(OpenFeatureProviderEvents.ProviderReady())
        val a = async(start = CoroutineStart.UNDISPATCHED) { t.observe().take(2).toList() }
        val b = async(start = CoroutineStart.UNDISPATCHED) { t.observe().take(2).toList() }
        t.send(OpenFeatureProviderEvents.ProviderStale())
        val la = a.await()
        val lb = b.await()
        assertTrue(la.size == 2 && lb.size == 2)
        assertIs<OpenFeatureProviderEvents.ProviderReady>(la[0])
        assertIs<OpenFeatureProviderEvents.ProviderReady>(lb[0])
        assertIs<OpenFeatureProviderEvents.ProviderStale>(la[1])
        assertIs<OpenFeatureProviderEvents.ProviderStale>(lb[1])
    }
}