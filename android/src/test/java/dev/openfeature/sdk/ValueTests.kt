package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test

class ValueTests {

    @Test
    fun testNull() {
        val value = Value.Null
        Assert.assertTrue(value.isNull())
    }

    @Test
    fun testIntShouldConvertToInt() {
        val value = Value.Integer(3)
        Assert.assertEquals(3, value.asInteger())
    }

    @Test
    fun testDoubleShouldConvertToDouble() {
        val value = Value.Double(3.14)
        Assert.assertEquals(3.14, value.asDouble()!!, 0.0)
    }

    @Test
    fun testBoolShouldConvertToBool() {
        val value = Value.Boolean(true)
        Assert.assertEquals(true, value.asBoolean())
    }

    @Test
    fun testStringShouldConvertToString() {
        val value = Value.String("test")
        Assert.assertEquals("test", value.asString())
    }

    @Test
    fun testListShouldConvertToList() {
        val value = Value.List(listOf(Value.Integer(3), Value.Integer(4)))
        Assert.assertEquals(listOf(Value.Integer(3), Value.Integer(4)), value.asList())
    }

    @Test
    fun testStructShouldConvertToStruct() {
        val value =
            Value.Structure(mapOf("field1" to Value.Integer(3), "field2" to Value.String("test")))
        Assert.assertEquals(
            value.asStructure(),
            mapOf("field1" to Value.Integer(3), "field2" to Value.String("test"))
        )
    }

    @Test
    fun testEmptyListAllowed() {
        val value = Value.List(listOf())
        Assert.assertEquals(listOf<Value>(), value.asList())
    }
}