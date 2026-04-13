package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.BooleanContextCaptureProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EvaluationContextMergeTests {

    @AfterTest
    fun tearDown() = runTest {
        OpenFeatureAPI.shutdown()
    }

    @Test
    fun mergeAttributes_overlayWinsOnCollision() {
        val base = ImmutableContext(
            targetingKey = "tk-base",
            attributes = mapOf("a" to Value.String("1"), "b" to Value.String("base"))
        )
        val overlay = ImmutableContext(
            targetingKey = "",
            attributes = mapOf("b" to Value.String("overlay"), "c" to Value.String("3"))
        )
        val merged = mergeEvaluationContexts(base, overlay)
        assertEquals("tk-base", merged.getTargetingKey())
        assertEquals(Value.String("overlay"), merged.getValue("b"))
        assertEquals(Value.String("1"), merged.getValue("a"))
        assertEquals(Value.String("3"), merged.getValue("c"))
    }

    @Test
    fun mergeTargeting_nonEmptyOverlayOverrides() {
        val base = ImmutableContext(targetingKey = "base", attributes = mapOf())
        val overlay = ImmutableContext(targetingKey = "overlay", attributes = mapOf())
        assertEquals("overlay", mergeEvaluationContexts(base, overlay).getTargetingKey())
    }

    @Test
    fun mergeTargeting_emptyOverlayInheritsBase() {
        val base = ImmutableContext(targetingKey = "base", attributes = mapOf())
        val overlay = ImmutableContext(targetingKey = "", attributes = mapOf())
        assertEquals("base", mergeEvaluationContexts(base, overlay).getTargetingKey())
    }

    @Test
    fun mergeTargeting_baseNull_overlayEmpty() {
        val overlay = ImmutableContext(targetingKey = "", attributes = mapOf("x" to Value.Boolean(true)))
        val merged = mergeEvaluationContexts(null, overlay)
        assertEquals("", merged.getTargetingKey())
        assertEquals(Value.Boolean(true), merged.getValue("x"))
    }

    @Test
    fun perCallContext_mergedWithGlobal_doesNotMutateGlobal() = runTest {
        val provider = BooleanContextCaptureProvider()
        OpenFeatureAPI.setProviderAndWait(provider)
        val global = ImmutableContext(
            targetingKey = "user-1",
            attributes = mapOf("persistent" to Value.String("p"))
        )
        OpenFeatureAPI.setEvaluationContextAndWait(global)

        val overlay = ImmutableContext(
            targetingKey = "",
            attributes = mapOf("feature_config" to Value.String("route-a"))
        )
        val client = OpenFeatureAPI.getClient()
        client.getBooleanValue(
            "flag",
            false,
            evaluationContext = overlay,
        )

        val seen = provider.lastBooleanContext!!
        assertEquals("user-1", seen.getTargetingKey())
        assertEquals(Value.String("p"), seen.getValue("persistent"))
        assertEquals(Value.String("route-a"), seen.getValue("feature_config"))

        assertEquals(global, OpenFeatureAPI.getEvaluationContext())
    }
}