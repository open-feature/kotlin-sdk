package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import dev.openfeature.kotlin.sdk.helpers.OverlyEmittingProvider
import dev.openfeature.kotlin.sdk.helpers.SlowProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenFeatureClientTests {

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun testShouldNowThrowIfHookHasDifferentTypeArgument() = runTest {
        OpenFeatureAPI.setProvider(NoOpProvider())
        OpenFeatureAPI.addHooks(listOf(GenericSpyHookMock()))
        val stringValue = OpenFeatureAPI.getClient().getStringValue("test", "defaultTest")
        assertEquals(stringValue, "defaultTest")
    }

    /**
     * Spec 1.7.1: The client MUST define a provider status accessor.
     */
    @Test
    fun testClientGetProviderStatusShouldReturnReadyWhenProviderIsInitialized() = runTest {
        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        val client = OpenFeatureAPI.getClient()
        assertEquals(OpenFeatureStatus.Ready, client.providerStatus)
    }

    /**
     * Spec 1.7.1: The client MUST define a provider status accessor.
     */
    @Test
    fun testClientGetProviderStatusShouldReturnErrorWhenProviderFailsToInitialize() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        val client = OpenFeatureAPI.getClient()
        val status = client.providerStatus
        assertTrue(status is OpenFeatureStatus.Error)
        assertTrue(
            (status as OpenFeatureStatus.Error).error is OpenFeatureError.ProviderNotReadyError
        )
    }

    /**
     * Spec 1.7.2.1: Provider status accessor must support RECONCILING state (static-context paradigm).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testClientGetProviderStatusShouldReturnReconcilingWhileContextIsBeingUpdated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val slowProvider = SlowProvider(dispatcher = dispatcher)
        val client = OpenFeatureAPI.getClient()

        // 1. Launch a background collector to record the sequential history
        val emittedStatuses = mutableListOf<OpenFeatureStatus>()
        val collectorJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.statusFlow.collect { emittedStatuses.add(it) }
        }

        OpenFeatureAPI.setProvider(slowProvider, dispatcher = dispatcher)

        // Wait for SlowProvider initialized (2000ms delay)
        advanceTimeBy(2001)

        // Trigger a context update (takes 2000ms natively)
        OpenFeatureAPI.setEvaluationContext(ImmutableContext(targetingKey = "user-123"), dispatcher)

        // Wait out the remaining 2000ms for slow context update finish
        advanceTimeBy(2001)

        // 2. Assert the sequence natively!
        // Expect: Initial NotReady -> Provider Ready -> Reconciling context -> Provider Ready again
        assertEquals(
            listOf(
                OpenFeatureStatus.NotReady,
                OpenFeatureStatus.Ready,
                OpenFeatureStatus.Reconciling,
                OpenFeatureStatus.Ready
            ),
            emittedStatuses
        )

        collectorJob.cancel()
    }

    @Test
    fun testClientGetProviderStatusShouldReturnFatalWhenProviderFailsFatal() = runTest {
        val fatalProvider = object : FeatureProvider by NoOpProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                throw OpenFeatureError.ProviderFatalError("test fatal error")
            }
        }
        OpenFeatureAPI.setProviderAndWait(fatalProvider)
        val client = OpenFeatureAPI.getClient()
        val status = client.providerStatus
        assertTrue(status is OpenFeatureStatus.Fatal)
        assertTrue(
            (status as OpenFeatureStatus.Fatal).error is OpenFeatureError.ProviderFatalError
        )
    }

    @Test
    fun testClientGetProviderStatusShouldReturnNotReadyBeforeProviderIsSet() = runTest {
        val client = OpenFeatureAPI.getClient()
        // No provider is set, so it should be NotReady
        assertEquals(OpenFeatureStatus.NotReady, client.providerStatus)
    }

    @Test
    fun testClientGetProviderStatusShouldReturnStaleWhenProviderEmitsStale() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val emittingProvider = OverlyEmittingProvider("emitting_provider")
        OpenFeatureAPI.setProviderAndWait(emittingProvider, dispatcher = dispatcher)

        val client = OpenFeatureAPI.getClient()
        assertEquals(OpenFeatureStatus.Ready, client.providerStatus)

        // Call track which forces the OverlyEmittingProvider to emit ProviderStale events
        client.track("test-stale-event")
        runCurrent()

        assertEquals(OpenFeatureStatus.Stale, client.providerStatus)
    }
}