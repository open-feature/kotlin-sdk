package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import dev.openfeature.kotlin.sdk.helpers.SlowProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
        assertEquals(OpenFeatureStatus.Ready, client.getProviderStatus())
    }

    /**
     * Spec 1.7.1: The client MUST define a provider status accessor.
     */
    @Test
    fun testClientGetProviderStatusShouldReturnErrorWhenProviderFailsToInitialize() = runTest {
        OpenFeatureAPI.setProviderAndWait(BrokenInitProvider())
        val client = OpenFeatureAPI.getClient()
        assertTrue(client.getProviderStatus() is OpenFeatureStatus.Error)
    }

    /**
     * Spec 1.7.2.1: Provider status accessor must support RECONCILING state (static-context paradigm).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testClientGetProviderStatusShouldReturnReconcilingWhileContextIsBeingUpdated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val slowProvider = SlowProvider(dispatcher = dispatcher)
        OpenFeatureAPI.setProvider(slowProvider, dispatcher = dispatcher)

        // Wait for SlowProvider initialized (2000ms delay)
        advanceTimeBy(2001)

        val client = OpenFeatureAPI.getClient()
        assertEquals(OpenFeatureStatus.Ready, client.getProviderStatus())

        // Trigger a context update (takes 2000ms natively)
        OpenFeatureAPI.setEvaluationContext(ImmutableContext(targetingKey = "user-123"), dispatcher)

        // Run execution queue deterministically to ensure coroutine reaches emit(Reconciling)
        runCurrent()
        assertEquals(OpenFeatureStatus.Reconciling, client.getProviderStatus())

        // Wait out the remaining 2000ms for slow context update finish
        advanceTimeBy(2001)
        assertEquals(OpenFeatureStatus.Ready, client.getProviderStatus())
    }
}