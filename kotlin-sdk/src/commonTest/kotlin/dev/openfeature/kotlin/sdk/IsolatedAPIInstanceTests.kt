package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import dev.openfeature.kotlin.sdk.helpers.SpyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsolatedAPIInstanceTests {

    private val instances = mutableListOf<OpenFeatureAPIInstance>()

    private fun createInstance(): OpenFeatureAPIInstance {
        val instance = createOpenFeatureAPIInstance()
        instances.add(instance)
        return instance
    }

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
        instances.forEach { it.shutdown() }
        OpenFeatureAPIInstance.clearBoundProviders()
    }

    @Test
    fun testCreateInstanceReturnsNewIndependentInstance() {
        val instance1 = createInstance()
        val instance2 = createInstance()
        assertNotSame(instance1, instance2)
        assertNotSame(instance1, OpenFeatureAPI)
    }

    @Test
    fun testIsolatedInstanceHasOwnProvider() = runTest {
        val provider1 = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "Provider 1"
            }
        )
        val provider2 = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "Provider 2"
            }
        )

        val instance = createInstance()
        OpenFeatureAPI.setProviderAndWait(provider1, ImmutableContext())
        instance.setProviderAndWait(provider2, ImmutableContext())

        assertEquals("Provider 1", OpenFeatureAPI.getProvider().metadata.name)
        assertEquals("Provider 2", instance.getProvider().metadata.name)
    }

    @Test
    fun testIsolatedInstanceHasOwnEvaluationContext() = runTest {
        val instance = createInstance()

        OpenFeatureAPI.setProviderAndWait(NoOpProvider())
        instance.setProviderAndWait(NoOpProvider())

        val ctx1 = ImmutableContext(targetingKey = "singleton-key")
        val ctx2 = ImmutableContext(targetingKey = "instance-key")

        OpenFeatureAPI.setEvaluationContextAndWait(ctx1)
        instance.setEvaluationContextAndWait(ctx2)

        assertEquals("singleton-key", OpenFeatureAPI.getEvaluationContext()?.getTargetingKey())
        assertEquals("instance-key", instance.getEvaluationContext()?.getTargetingKey())
    }

    @Test
    fun testIsolatedInstanceHasOwnHooks() = runTest {
        val instance = createInstance()

        val hook1 = GenericSpyHookMock()
        val hook2 = GenericSpyHookMock()

        OpenFeatureAPI.addHooks(listOf(hook1))
        instance.addHooks(listOf(hook2))

        assertEquals(listOf(hook1), OpenFeatureAPI.hooks)
        assertEquals(listOf(hook2), instance.hooks)
    }

    @Test
    fun testIsolatedInstanceHasOwnStatus() = runTest {
        val instance = createInstance()

        instance.setProviderAndWait(DoSomethingProvider(), ImmutableContext())

        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        assertEquals(OpenFeatureStatus.Ready, instance.getStatus())
    }

    @Test
    fun testIsolatedInstanceClientEvaluatesIndependently() = runTest {
        val instance = createInstance()
        instance.setProviderAndWait(DoSomethingProvider(), ImmutableContext())

        val client = instance.getClient()
        // DoSomethingProvider returns !defaultValue for booleans
        val result = client.getBooleanValue("flag", false)
        assertTrue(result)
    }

    @Test
    fun testSingletonStillWorksAfterCreatingInstances() = runTest {
        createInstance()

        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider(), ImmutableContext())
        val client = OpenFeatureAPI.getClient()
        val result = client.getBooleanValue("flag", false)
        assertTrue(result)
    }

    @Test
    fun testShutdownInstanceDoesNotAffectSingleton() = runTest {
        val instance = createInstance()

        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider(), ImmutableContext())
        instance.setProviderAndWait(DoSomethingProvider(), ImmutableContext())

        instance.shutdown()

        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
        assertEquals(OpenFeatureStatus.NotReady, instance.getStatus())
    }

    @Test
    fun testShutdownSingletonDoesNotAffectInstance() = runTest {
        val instance = createInstance()

        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider(), ImmutableContext())
        instance.setProviderAndWait(DoSomethingProvider(), ImmutableContext())

        OpenFeatureAPI.shutdown()

        assertEquals(OpenFeatureStatus.NotReady, OpenFeatureAPI.getStatus())
        assertEquals(OpenFeatureStatus.Ready, instance.getStatus())
    }

    @Test
    fun testProviderCannotBeBoundToMultipleInstancesSync() = runTest {
        val instance = createInstance()
        val sharedProvider = DoSomethingProvider()

        OpenFeatureAPI.setProviderAndWait(sharedProvider, ImmutableContext())
        instance.setProviderAndWait(sharedProvider, ImmutableContext())

        assertTrue(instance.getStatus() is OpenFeatureStatus.Error)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testProviderCannotBeBoundToMultipleInstancesAsync() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val instance = createInstance()
        val sharedProvider = DoSomethingProvider()

        OpenFeatureAPI.setProviderAndWait(sharedProvider, ImmutableContext())
        instance.setProvider(sharedProvider, dispatcher = testDispatcher)
        advanceUntilIdle()

        assertTrue(instance.getStatus() is OpenFeatureStatus.Error)
    }

    @Test
    fun testProviderCanBeReusedAfterShutdown() = runTest {
        val instance = createInstance()
        val provider = DoSomethingProvider()

        instance.setProviderAndWait(provider, ImmutableContext())
        instance.shutdown()

        // Provider is now unbound, can be used by another instance
        OpenFeatureAPI.setProviderAndWait(provider, ImmutableContext())
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testProviderCanBeReusedAfterClearProvider() = runTest {
        val instance = createInstance()
        val provider = DoSomethingProvider()

        instance.setProviderAndWait(provider, ImmutableContext())
        instance.clearProvider()

        OpenFeatureAPI.setProviderAndWait(provider, ImmutableContext())
        assertEquals(OpenFeatureStatus.Ready, OpenFeatureAPI.getStatus())
    }

    @Test
    fun testMultipleIsolatedInstancesAreIndependent() = runTest {
        val instance1 = createInstance()
        val instance2 = createInstance()

        val provider1 = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "Instance1 Provider"
            }
        )
        val provider2 = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "Instance2 Provider"
            }
        )

        instance1.setProviderAndWait(provider1, ImmutableContext())
        instance2.setProviderAndWait(provider2, ImmutableContext())

        assertEquals("Instance1 Provider", instance1.getProvider().metadata.name)
        assertEquals("Instance2 Provider", instance2.getProvider().metadata.name)

        instance1.shutdown()
        assertEquals(OpenFeatureStatus.NotReady, instance1.getStatus())
        assertEquals(OpenFeatureStatus.Ready, instance2.getStatus())
    }

    @Test
    fun testIsolatedInstanceClientHooks() = runTest {
        val instance = createInstance()
        instance.setProviderAndWait(NoOpProvider(), ImmutableContext())

        val client = instance.getClient()
        val hook = GenericSpyHookMock()
        client.addHooks(listOf(hook))

        client.getBooleanValue("test", false)
        assertEquals(1, hook.finallyCalledAfter)
    }

    @Test
    fun testIsolatedInstanceContextIsNull() {
        val instance = createInstance()
        assertNull(instance.getEvaluationContext())
    }

    @Test
    fun testSwappingProviderOnInstance() = runTest {
        val instance = createInstance()

        val provider1 = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "First"
            }
        )
        val provider2 = DoSomethingProvider(
            metadata = object : ProviderMetadata {
                override val name: String = "Second"
            }
        )

        instance.setProviderAndWait(provider1, ImmutableContext())
        assertEquals("First", instance.getProvider().metadata.name)

        instance.setProviderAndWait(provider2, ImmutableContext())
        assertEquals("Second", instance.getProvider().metadata.name)
    }

    @Test
    fun testDistinctProvidersWithSameEqualityAreNotConflated() = runTest {
        val instance1 = createInstance()
        val instance2 = createInstance()

        // Two distinct provider objects that are structurally equal via equals/hashCode
        val provider1 = ValueEqualProvider("shared-name")
        val provider2 = ValueEqualProvider("shared-name")
        assertEquals(provider1, provider2, "Precondition: providers are structurally equal")

        instance1.setProviderAndWait(provider1, ImmutableContext())
        instance2.setProviderAndWait(provider2, ImmutableContext())

        // Both should succeed — distinct objects should not be conflated
        assertEquals(OpenFeatureStatus.Ready, instance1.getStatus())
        assertEquals(OpenFeatureStatus.Ready, instance2.getStatus())
    }

    @Test
    fun testNoOpProviderSubclassIsGuarded() = runTest {
        val instance1 = createInstance()
        val instance2 = createInstance()

        // A single provider that happens to extend NoOpProvider
        val sharedSubclass = object : NoOpProvider() {
            override val metadata = object : ProviderMetadata {
                override val name: String = "Custom NoOp Subclass"
            }
        }

        instance1.setProviderAndWait(sharedSubclass, ImmutableContext())
        instance2.setProviderAndWait(sharedSubclass, ImmutableContext())

        // The subclass is not the instance's private fallback, so the guard must fire
        assertEquals(OpenFeatureStatus.Ready, instance1.getStatus())
        assertTrue(instance2.getStatus() is OpenFeatureStatus.Error)
    }

    @Test
    fun testClearProviderUnbindsEvenIfShutdownThrows() = runTest {
        val instance1 = createInstance()
        val instance2 = createInstance()

        var shouldThrow = true
        val throwingProvider = object : NoOpProvider() {
            override val metadata = object : ProviderMetadata {
                override val name: String = "Throwing Provider"
            }
            override fun shutdown() {
                if (shouldThrow) throw RuntimeException("shutdown failed")
            }
        }

        instance1.setProviderAndWait(throwingProvider, ImmutableContext())
        assertEquals(OpenFeatureStatus.Ready, instance1.getStatus())

        // clearProvider should release the binding even though shutdown throws
        try { instance1.clearProvider() } catch (_: RuntimeException) {}

        // Another instance should now be able to bind the same provider
        instance2.setProviderAndWait(throwingProvider, ImmutableContext())
        assertEquals(OpenFeatureStatus.Ready, instance2.getStatus())

        // Disable throwing so tearDown can clean up
        shouldThrow = false
    }

    @Test
    fun testReSettingSameProviderDoesNotShutItDown() = runTest {
        val instance = createInstance()
        val provider = SpyProvider()

        instance.setProviderAndWait(provider)
        instance.setProviderAndWait(provider)

        assertEquals(0, provider.shutdownCalls.value)
        assertEquals(OpenFeatureStatus.Ready, instance.getStatus())
    }
}

/**
 * A NoOpProvider that implements value equality via [name], so two distinct instances
 * with the same name are structurally equal. Used to verify the binding registry
 * uses identity, not equality.
 */
private class ValueEqualProvider(val name: String) : NoOpProvider() {
    override val metadata = object : ProviderMetadata {
        override val name: String = this@ValueEqualProvider.name
    }

    override fun equals(other: Any?): Boolean =
        other is ValueEqualProvider && name == other.name

    override fun hashCode(): Int = name.hashCode()
}