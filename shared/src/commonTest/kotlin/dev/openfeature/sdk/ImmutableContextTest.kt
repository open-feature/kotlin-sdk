package dev.openfeature.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail


class ImmutableContextTest {

    @Test
    fun `should be immutable - modifications to input map should not affect context`() {
        val mutableAttributes = mutableMapOf("key1" to Value.String("value1"), "key2" to Value.Integer(42))

        val context = ImmutableContext("targetingKey", mutableAttributes)

        assertEquals("value1", context.getValue("key1")?.asString())
        assertEquals(42, context.getValue("key2")?.asInteger())

        // Modify the original mutable map
        mutableAttributes["key1"] = Value.String("modified")
        mutableAttributes["key3"] = Value.Boolean(true)

        // Verify that the context is not affected by the modifications
        assertEquals("value1", context.getValue("key1")?.asString())
        assertEquals(42, context.getValue("key2")?.asInteger())
        assertNull(context.getValue("key3"))
    }

    @Test
    fun `should be immutable - modifications to returned map should not affect context`() {
        val attributes = mapOf("key1" to Value.String("value1"))
        val context = ImmutableContext("targetingKey", attributes)

        val returnedMap = context.asMap()
        try {
            if (returnedMap is MutableMap) {
                returnedMap["key2"] = Value.String("newValue")
                fail("Returned map should be immutable")
            }
        } catch (_: UnsupportedOperationException) {
        }

        assertEquals("value1", context.getValue("key1")?.asString())
        assertNull(context.getValue("key2"))
    }

    @Test
    fun `should be immutable - nested structures should be immutable`() {
        val nestedMap = mutableMapOf("nestedKey" to Value.String("nestedValue"))
        val attributes = mapOf("key1" to Value.Structure(nestedMap))
        val context = ImmutableContext("targetingKey", attributes)

        nestedMap["nestedKey"] = Value.String("modified")
        nestedMap["newNestedKey"] = Value.String("newValue")

        val contextNestedStructure = context.getValue("key1")?.asStructure()

        assertEquals("nestedValue", contextNestedStructure?.get("nestedKey")?.asString())
        assertNull(contextNestedStructure?.get("newNestedKey"))
    }

    @Test
    fun `should be immutable - nested lists should be immutable`() {
        val nestedList = mutableListOf(Value.String("item1"), Value.Integer(42))
        val attributes = mapOf("key1" to Value.List(nestedList))
        val context = ImmutableContext("targetingKey", attributes)

        nestedList[0] = Value.String("modified")
        nestedList.add(Value.Boolean(true))

        val contextNestedList = context.getValue("key1")?.asList()

        assertEquals("item1", contextNestedList?.get(0)?.asString())
        assertEquals(42, contextNestedList?.get(1)?.asInteger())
        assertEquals(2, contextNestedList?.size)
    }
}