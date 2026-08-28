package dev.openfeature.kotlin.sdk.telemetry

import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.HookContext
import dev.openfeature.kotlin.sdk.NoOpProvider
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.Reason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryIntegrationTest {

    @AfterTest
    fun cleanup() = runTest {
        OpenFeatureAPI.clearProvider()
    }

    @Test
    fun `test full flag evaluation successfully publishes OTEL metric semantics to local sink`() = runTest {
        val capturedTelemetryEvents = mutableListOf<EvaluationEvent>()

        val mockOtelHook = object : Hook<Any> {
            override fun finallyAfter(
                hookContext: HookContext<Any>,
                details: FlagEvaluationDetails<Any>,
                hints: Map<String, Any>
            ) {
                val event = Telemetry.createEvaluationEvent(hookContext, details)
                capturedTelemetryEvents.add(event)
            }
        }

        val provider = NoOpProvider()
        OpenFeatureAPI.setProviderAndWait(provider)

        val client = OpenFeatureAPI.getClient()
        client.addHooks(listOf(mockOtelHook))

        val flagResult = client.getBooleanValue("login-button", false)
        assertFalse(flagResult)

        assertEquals(1, capturedTelemetryEvents.size)

        val metric = capturedTelemetryEvents.first()
        assertEquals(Telemetry.FLAG_EVALUATION_EVENT_NAME, metric.name)
        assertEquals("login-button", metric.attributes[Telemetry.TELEMETRY_KEY])
        // NoOpProvider inherently returns "Passed in default" for variants, but we still expect telemetry Value propagation per Appendix D footnote [2].
        assertEquals("Passed in default", metric.attributes[Telemetry.TELEMETRY_VARIANT])
        assertEquals(false, metric.attributes[Telemetry.TELEMETRY_VALUE])
        assertEquals(Reason.DEFAULT.name.lowercase(), metric.attributes[Telemetry.TELEMETRY_REASON])
    }
}