package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Retiring a replaced provider must not happen on the caller's thread, or registering a provider
 * from a UI thread blocks on its predecessor's whole teardown.
 *
 * JVM-only because it needs to block a real thread, which common test code cannot portably do.
 */
@OptIn(ExperimentalIsolatedApi::class)
class ProviderRetirementTest {

    @AfterTest
    fun tearDown() {
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    private class BlockingShutdownProvider : NoOpProvider() {
        val shutdownEntered = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)

        override fun shutdown() {
            shutdownEntered.countDown()
            releaseShutdown.await(30, TimeUnit.SECONDS)
            super.shutdown()
        }
    }

    @Test
    fun setProviderDoesNotBlockOnTheOutgoingProvidersShutdown() {
        val instance = createOpenFeatureAPIInstance()
        val outgoing = BlockingShutdownProvider()
        runBlocking { instance.setProviderAndWait(outgoing) }

        val returned = CountDownLatch(1)
        thread {
            instance.setProvider(NoOpProvider())
            returned.countDown()
        }

        try {
            assertTrue(
                returned.await(5, TimeUnit.SECONDS),
                "setProvider blocked on the outgoing provider's shutdown"
            )
            assertTrue(
                outgoing.shutdownEntered.await(5, TimeUnit.SECONDS),
                "the outgoing provider was never shut down"
            )
        } finally {
            outgoing.releaseShutdown.countDown()
        }
    }

    @Test
    fun aProviderRegisteredAgainWhileItsRetirementIsPendingIsNotTornDown() {
        val instance = createOpenFeatureAPIInstance()
        val provider = BlockingShutdownProvider()
        runBlocking { instance.setProviderAndWait(provider) }

        instance.setProvider(NoOpProvider())
        assertTrue(
            provider.shutdownEntered.await(5, TimeUnit.SECONDS),
            "the replaced provider was never retired"
        )

        // Registered again while its own teardown is still running: initializing over that would
        // leave the live registration reporting the status its retirement reset.
        instance.setProvider(provider)
        provider.releaseShutdown.countDown()

        runBlocking {
            withTimeout(5.seconds) {
                instance.statusFlow.first { it == OpenFeatureStatus.Ready }
            }
        }
        assertTrue(instance.getProvider() === provider)
        assertEquals(OpenFeatureStatus.Ready, instance.getStatus())
    }

    @Test
    fun setProviderAndWaitReportsTheOutgoingProviderDown() {
        val instance = createOpenFeatureAPIInstance()
        val outgoing = BlockingShutdownProvider()
        outgoing.releaseShutdown.countDown()
        runBlocking { instance.setProviderAndWait(outgoing) }

        // A suspending caller can be told the provider it replaced is actually down.
        runBlocking { instance.setProviderAndWait(NoOpProvider()) }

        assertEquals(0, outgoing.shutdownEntered.count)
    }
}