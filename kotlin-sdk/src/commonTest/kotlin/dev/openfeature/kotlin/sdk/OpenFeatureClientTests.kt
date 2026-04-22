package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

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

    @Test
    fun testEvaluationContextMergingPrecedence() = runTest {
        val globalContext = ImmutableContext(
            targetingKey = "global-user",
            attributes = mapOf(
                "globalKey" to Value.String("globalValue"),
                "conflictKey" to Value.String("globalConflict")
            )
        )
        val domainContext = ImmutableContext(
            targetingKey = "domain-user",
            attributes = mapOf(
                "domainKey" to Value.String("domainValue"),
                "conflictKey" to Value.String("domainConflict")
            )
        )

        OpenFeatureAPI.setEvaluationContextAndWait(globalContext)
        OpenFeatureAPI.setEvaluationContextAndWait("test-domain", domainContext)

        val mergedContext = OpenFeatureAPI.getEvaluationContext("test-domain")

        assertEquals("domain-user", mergedContext?.getTargetingKey())
        assertEquals("globalValue", mergedContext?.getValue("globalKey")?.asString())
        assertEquals("domainValue", mergedContext?.getValue("domainKey")?.asString())
        assertEquals("domainConflict", mergedContext?.getValue("conflictKey")?.asString())
    }

    @Test
    fun testEvaluationContextCachePreventsSubsequentAllocations() = runTest {
        val globalContext = ImmutableContext(targetingKey = "global")
        val domainContext = ImmutableContext(targetingKey = "domain")

        OpenFeatureAPI.setEvaluationContextAndWait(globalContext)
        OpenFeatureAPI.setEvaluationContextAndWait("cache-domain", domainContext)

        val firstFetch = OpenFeatureAPI.getEvaluationContext("cache-domain")
        val secondFetch = OpenFeatureAPI.getEvaluationContext("cache-domain")

        assertNotNull(firstFetch)
        assertSame(firstFetch, secondFetch) // O(1) GC Leak Protection Validation Wrapper
    }

    @Test
    fun testProviderOnContextSetReceivesMergedContext() = runTest {
        var capturedNewContext: EvaluationContext? = null
        val provider = object : NoOpProvider() {
            override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
                capturedNewContext = newContext
            }
        }

        OpenFeatureAPI.setProviderAndWait("mock-domain", provider)
        OpenFeatureAPI.setEvaluationContextAndWait(
            ImmutableContext(attributes = mapOf("global" to Value.String("globalVal")))
        )
        OpenFeatureAPI.setEvaluationContextAndWait(
            "mock-domain",
            ImmutableContext(attributes = mapOf("domain" to Value.String("domainVal")))
        )

        val captured = capturedNewContext
        assertNotNull(captured)
        assertEquals("globalVal", captured.getValue("global")?.asString())
        assertEquals("domainVal", captured.getValue("domain")?.asString())
    }
}