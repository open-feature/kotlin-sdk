package dev.openfeature.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ValueTests {

    @Test
    fun testNull() {
        val value = Value.Null
        assertTrue(value.isNull())
    }

    @Test
    fun testIntShouldConvertToInt() {
        val value = Value.Integer(3)
        assertEquals(3, value.asInteger())
    }

    @Test
    fun testDoubleShouldConvertToDouble() {
        val value = Value.Double(3.14)
        assertEquals(3.14, value.asDouble()!!, 0.0)
    }

    @Test
    fun testBoolShouldConvertToBool() {
        val value = Value.Boolean(true)
        assertEquals(true, value.asBoolean())
    }

    @Test
    fun testStringShouldConvertToString() {
        val value = Value.String("test")
        assertEquals("test", value.asString())
    }

    @Test
    fun testListShouldConvertToList() {
        val value = Value.List(listOf(Value.Integer(3), Value.Integer(4)))
        assertEquals(listOf(Value.Integer(3), Value.Integer(4)), value.asList())
    }

    @Test
    fun testStructShouldConvertToStruct() {
        val value =
            Value.Structure(mapOf("field1" to Value.Integer(3), "field2" to Value.String("test")))
        assertEquals(
            value.asStructure(),
            mapOf("field1" to Value.Integer(3), "field2" to Value.String("test"))
        )
    }

    @Test
    fun testEmptyListAllowed() {
        val value = Value.List(listOf())
        assertEquals(listOf<Value>(), value.asList())
    }
}