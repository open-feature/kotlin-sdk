package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.helpers.BrokenInitProvider
import dev.openfeature.kotlin.sdk.helpers.DoSomethingProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class DomainE2ETest {
    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    /**
     * Requirement 1.1.2.3: "If a provider has not been set for the domain ... of a Client,
     * the provider will default to the default provider."
     */
    @Test
    fun testDefaultProviderFallback() = runTest {
        // Setup default provider
        OpenFeatureAPI.setProviderAndWait(DoSomethingProvider())

        // Use a domain-bound client
        val client = OpenFeatureAPI.getClient(domain = "my-domain")

        // Evaluate boolean flag to verify it returns correctly using default provider fallback
        val result = client.getBooleanValue("test-flag", false)

        // DoSomethingProvider returns inverted boolean by default
        assertEquals(true, result)
    }

    /**
     * Requirement 1.1.2.2: "The API MUST provide a function for binding a given provider to one or more domains.
     * If a provider is bound to a domain, the provider is used to evaluate flags from clients associated with that domain."
     */
    @Test
    fun testDomainSpecificProvider() = runTest {
        // Setup fallback default provider
        val defaultProvider = DoSomethingProvider()
        OpenFeatureAPI.setProviderAndWait(defaultProvider)

        // Setup domain specific provider
        val domainProvider = NoOpProvider()
        OpenFeatureAPI.setProviderAndWait("specific-domain", domainProvider)

        val defaultClient = OpenFeatureAPI.getClient(domain = "default-domain")
        val specificClient = OpenFeatureAPI.getClient(domain = "specific-domain")

        // Specific client should use NoOpProvider, returning the default user value
        val specificResult = specificClient.getBooleanValue("test-flag", defaultValue = true)
        assertEquals(true, specificResult)

        // Default client should fall back to DoSomethingProvider, which inverts the default
        val defaultResult = defaultClient.getBooleanValue("test-flag", defaultValue = true)
        assertEquals(false, defaultResult)

        assertEquals(domainProvider, OpenFeatureAPI.getProvider("specific-domain"))
        assertEquals(defaultProvider, OpenFeatureAPI.getProvider("default-domain"))
    }

    /**
     * Requirement 5.2.1: "The API MUST provide a function to get the status of the provider."
     * Validates that state is isolated per domain, so a broken provider doesn't halt other domains.
     */
    @Test
    fun testDomainStatusIsolation() = runTest {
        OpenFeatureAPI.setProviderAndWait("fast-domain", NoOpProvider())

        val brokenProvider = BrokenInitProvider()
        OpenFeatureAPI.setProviderAndWait("broken-domain", brokenProvider)

        val fastStatus = OpenFeatureAPI.getProviderStatus("fast-domain")
        val brokenStatus = OpenFeatureAPI.getProviderStatus("broken-domain")

        assertIs<OpenFeatureStatus.Ready>(fastStatus)
        assertIs<OpenFeatureStatus.Error>(brokenStatus)
    }

    /**
     * Requirement 3.2.2: "The client MUST provide a method for adding context. Client context MUST NOT be shared
     * between other clients."
     * Requirement 3.1.2: Global execution context should be inherited if domain context is missing.
     */
    @Test
    fun testDomainSpecificEvaluationContext() = runTest {
        val globalContext = ImmutableContext(targetingKey = "global")
        val domainContext = ImmutableContext(targetingKey = "domain-specific")

        // Set global context
        OpenFeatureAPI.setEvaluationContextAndWait(globalContext)
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext())

        // Unset domain falls back to global
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext("my-domain"))

        // Set domain context
        OpenFeatureAPI.setEvaluationContextAndWait("my-domain", domainContext)

        // Domain context is now specific
        assertEquals(domainContext, OpenFeatureAPI.getEvaluationContext("my-domain"))

        // Global context remains unchanged
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext())

        // Mutating global context does not affect bounded domain context
        val newGlobalContext = ImmutableContext(targetingKey = "new-global")
        OpenFeatureAPI.setEvaluationContextAndWait(newGlobalContext)

        assertEquals(newGlobalContext, OpenFeatureAPI.getEvaluationContext())
        assertEquals(domainContext, OpenFeatureAPI.getEvaluationContext("my-domain"))
        assertEquals(newGlobalContext, OpenFeatureAPI.getEvaluationContext("fallback-domain"))
    }

    /**
     * Requirement 5.3.3: "Event listeners MUST only be triggered for events associated with their client's domain."
     * Validates that events on one domain are not leaked or propagated to observers of another domain.
     */
    @Test
    fun testEventStreamIsolation() = runTest {
        val domainA = "domain-a"
        val domainB = "domain-b"

        val flowA = MutableSharedFlow<OpenFeatureProviderEvents>()
        val flowB = MutableSharedFlow<OpenFeatureProviderEvents>()

        val providerA = object : FeatureProvider by NoOpProvider() {
            override fun observe() = flowA
        }
        val providerB = object : FeatureProvider by NoOpProvider() {
            override fun observe() = flowB
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(domainA, providerA, dispatcher = testDispatcher)
        OpenFeatureAPI.setProviderAndWait(domainB, providerB, dispatcher = testDispatcher)

        val eventsA = mutableListOf<OpenFeatureProviderEvents>()
        val eventsB = mutableListOf<OpenFeatureProviderEvents>()

        val jobA = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>(domainA).collect { eventsA.add(it) }
        }
        val jobB = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>(domainB).collect { eventsB.add(it) }
        }

        testScheduler.advanceUntilIdle()

        // providers emit their Ready events initially if set up, but MockEventProvider relies on manual emission.
        flowA.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()

        assertEquals(1, eventsA.size)
        assertIs<OpenFeatureProviderEvents.ProviderStale>(eventsA.first())
        // domain B should not receive A's event
        assertEquals(0, eventsB.size)
        flowB.emit(OpenFeatureProviderEvents.ProviderReady())
        testScheduler.advanceUntilIdle()

        assertEquals(1, eventsA.size) // still 1
        assertEquals(1, eventsB.size)
        assertIs<OpenFeatureProviderEvents.ProviderReady>(eventsB.first())

        jobA.cancelAndJoin()
        jobB.cancelAndJoin()
    }

    /**
     * Requirement 4.1.3: "Client hooks MUST ONLY execute for flags evaluated by that client."
     * Validates that hooks bound specifically to one domain are completely isolated from global
     * contexts or evaluations triggered natively by other standalone domains.
     */
    @Test
    fun testHookPropagationAndIsolation() = runTest {
        val globalProvider = DoSomethingProvider()
        OpenFeatureAPI.setProviderAndWait(globalProvider)

        val clientA = OpenFeatureAPI.getClient(domain = "domain-a")
        val clientB = OpenFeatureAPI.getClient(domain = "domain-b")

        var hookAInvocations = 0
        var hookGlobalInvocations = 0

        val hookA = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                hookAInvocations++
            }
        }
        val hookGlobal = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                hookGlobalInvocations++
            }
        }

        clientA.addHooks(listOf(hookA))
        OpenFeatureAPI.addHooks(listOf(hookGlobal))

        // Trigger evaluation on Client A (should hit hook A and global hook)
        clientA.getBooleanValue("flag", false)
        assertEquals(1, hookAInvocations)
        assertEquals(1, hookGlobalInvocations)

        // Trigger evaluation on Client B (should hit ONLY global hook, NOT hook A)
        clientB.getBooleanValue("flag", false)
        assertEquals(1, hookAInvocations)
        assertEquals(2, hookGlobalInvocations)

        OpenFeatureAPI.clearHooks()
    }

    /**
     * Requirement 1.1.2.3 and 5.1.3: Changing the bound provider MUST execute its initialization lifecycle.
     * Validates that if a domain specifically falls back to the global provider, changing that global provider
     * properly bubbles status events dynamically to observers of the fallback domain.
     */
    @Test
    fun testDynamicFallbackLifecycleUpdates() = runTest {
        val flow1 = MutableSharedFlow<OpenFeatureProviderEvents>()
        val defaultProvider1 = object : FeatureProvider by NoOpProvider() {
            override fun observe() = flow1
        }

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        OpenFeatureAPI.setProviderAndWait(defaultProvider1, dispatcher = testDispatcher)

        val events = mutableListOf<OpenFeatureProviderEvents>()
        val job = launch {
            OpenFeatureAPI.observe<OpenFeatureProviderEvents>("unbound-domain").collect { events.add(it) }
        }

        testScheduler.advanceUntilIdle()

        flow1.emit(OpenFeatureProviderEvents.ProviderReady())
        testScheduler.advanceUntilIdle()

        assertEquals(1, events.size)
        assertIs<OpenFeatureProviderEvents.ProviderReady>(events.last())

        // Swap the global fallback provider dynamically
        val flow2 = MutableSharedFlow<OpenFeatureProviderEvents>()
        val defaultProvider2 = object : FeatureProvider by NoOpProvider() {
            override fun observe() = flow2
        }
        OpenFeatureAPI.setProviderAndWait(defaultProvider2, dispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        flow2.emit(OpenFeatureProviderEvents.ProviderStale())
        testScheduler.advanceUntilIdle()

        // We received Stale on the unbound domain because it correctly maps to the NEW global provider!
        // We also received ProviderReady from the SDK when defaultProvider2 was set.
        assertEquals(3, events.size)
        assertIs<OpenFeatureProviderEvents.ProviderStale>(events.last())

        job.cancelAndJoin()
    }

    /**
     * Requirement 4.3.4: "When a flag is evaluated, hooks MUST be executed in the following order:
     * before: API, Client, Invocation."
     * Validates the execution order of dynamically stacked hooks from varying logical layers.
     */
    @Test
    fun testHookExecutionOrder() = runTest {
        val globalProvider = DoSomethingProvider()
        OpenFeatureAPI.setProviderAndWait(globalProvider)

        val executionOrder = mutableListOf<String>()

        val globalHook = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                executionOrder.add("API")
            }
        }

        val clientHook = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                executionOrder.add("Client")
            }
        }

        val invocationHook = object : Hook<Boolean> {
            override fun before(ctx: HookContext<Boolean>, hints: Map<String, Any>) {
                executionOrder.add("Invocation")
            }
        }

        OpenFeatureAPI.addHooks(listOf(globalHook))

        val client = OpenFeatureAPI.getClient("hook-order-domain")
        client.addHooks(listOf(clientHook))

        val options = FlagEvaluationOptions(hooks = listOf(invocationHook))
        client.getBooleanValue("test-flag", false, options)

        assertEquals(listOf("API", "Client", "Invocation"), executionOrder)

        OpenFeatureAPI.clearHooks()
    }

    /**
     * Requirement: `setProvider` for a specific domain with an `initialContext` should
     * only update the context for that domain, and initialize the provider with it.
     * The global context should remain untouched.
     */
    @Test
    fun testSetProviderIsolatesInitialContext() = runTest {
        var initializedContext: EvaluationContext? = null
        val testProvider = object : FeatureProvider by NoOpProvider() {
            override suspend fun initialize(initialContext: EvaluationContext?) {
                initializedContext = initialContext
            }
        }
        val domainContext = ImmutableContext(targetingKey = "domain-only")
        val globalContext = ImmutableContext(targetingKey = "global-fallback")

        OpenFeatureAPI.setEvaluationContextAndWait(globalContext)

        // Set the provider for a specific domain with an initial context
        OpenFeatureAPI.setProviderAndWait("isolated-domain", testProvider, domainContext)

        // Verify the provider received the domain context upon initialization
        assertEquals(domainContext, initializedContext)

        // Verify the domain context was isolated and properly written to the domain state
        assertEquals(domainContext, OpenFeatureAPI.getEvaluationContext("isolated-domain"))

        // Verify the global context was NOT mutated by the domain-specific setProvider call
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext())
    }

    /**
     * Requirement: `setEvaluationContext` with a null domain should act as a global update.
     * It must update the global concept and propagate only to domains without specific overrides.
     */
    @Test
    fun testSetEvaluationContextWithNullDomainUpdatesGlobalAndDefault() = runTest {
        // Give "override-domain" a specific context overriding the global one
        val overrideContext = ImmutableContext(targetingKey = "override-context")
        OpenFeatureAPI.setEvaluationContextAndWait("override-domain", overrideContext)

        // Set providers
        OpenFeatureAPI.setProviderAndWait("override-domain", NoOpProvider())
        OpenFeatureAPI.setProviderAndWait("fallback-domain", NoOpProvider())
        OpenFeatureAPI.setProviderAndWait(NoOpProvider()) // default domain

        // Set global context passing a null domain
        val globalContext = ImmutableContext(targetingKey = "global-context")
        OpenFeatureAPI.setEvaluationContextAndWait(null as String?, globalContext)

        // Verify the global context was updated
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext())

        // Verify that default domain gets the global context
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext(null))

        // Verify that domains without specific overrides fall back to the global context
        assertEquals(globalContext, OpenFeatureAPI.getEvaluationContext("fallback-domain"))

        // Verify that domain with a specific override keeps its context
        assertEquals(overrideContext, OpenFeatureAPI.getEvaluationContext("override-domain"))
    }

    /**
     * Requirement: `setEvaluationContextAndWait` with a null domain should suspend until all
     * providers across all domains without specific overrides have finished `onContextSet`.
     * Furthermore, it should utilize `coroutineScope` parallel execution.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun testSetEvaluationContextAndWaitWithNullDomainSuspendsUntilAllProvidersComplete() = runTest {
        var completedProviders = 0

        val slowProvider = object : FeatureProvider by NoOpProvider() {
            override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
                delay(100) // Simulate a slow context update
                completedProviders++
            }
        }

        OpenFeatureAPI.setProviderAndWait("domain-1", slowProvider)
        OpenFeatureAPI.setProviderAndWait("domain-2", slowProvider)
        OpenFeatureAPI.setProviderAndWait("domain-3", slowProvider)

        val newContext = ImmutableContext(targetingKey = "new-context")

        val timeToRun = currentTime
        // Null domain triggers a global update targeting all fallback domains (1, 2, 3)
        OpenFeatureAPI.setEvaluationContextAndWait(null as String?, newContext)
        val elapsed = currentTime - timeToRun

        // By checking immediately after the call, we prove it cleanly suspended the caller
        assertEquals(3, completedProviders)

        // In virtual time, since all 3 delays of 100ms executed in parallel,
        // the elapsed virtual time should be precisely 100ms, not 300ms!
        assertEquals(100L, elapsed)
    }
}