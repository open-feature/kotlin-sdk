package dev.openfeature.kotlin.sdk

import dev.openfeature.kotlin.sdk.helpers.GenericSpyHookMock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}