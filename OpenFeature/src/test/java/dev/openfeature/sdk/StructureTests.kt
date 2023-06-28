package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test
import java.util.Date

class StructureTests {

    @Test
    fun testNoArgIsEmpty() {
        val structure = MutableContext()
        Assert.assertTrue(structure.asMap().keys.isEmpty())
    }

    @Test
    fun testArgShouldContainNewMap() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val structure = MutableStructure(map)

        Assert.assertEquals("test", structure.getValue("key")?.asString())
        Assert.assertEquals(map, structure.asMap())
    }

    @Test
    fun testAddAndGetReturnValues() {
        val now = Date()
        val structure = MutableStructure()
        structure.add("bool", Value.Boolean(true))
        structure.add("string", Value.String("val"))
        structure.add("int", Value.Integer(13))
        structure.add("double", Value.Double(0.5))
        structure.add("date", Value.Instant(now))
        structure.add("list", Value.List(listOf()))
        structure.add("structure", Value.Structure(mapOf()))

        Assert.assertEquals(true, structure.getValue("bool")?.asBoolean())
        Assert.assertEquals("val", structure.getValue("string")?.asString())
        Assert.assertEquals(13, structure.getValue("int")?.asInteger())
        Assert.assertEquals(0.5, structure.getValue("double")?.asDouble())
        Assert.assertEquals(now, structure.getValue("date")?.asInstant())
        Assert.assertEquals(listOf<Value>(), structure.getValue("list")?.asList())
        Assert.assertEquals(mapOf<String, Value>(), structure.getValue("structure")?.asStructure())
    }

    @Test
    fun testCompareStructure() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val map2: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val structure1 = MutableStructure(map)
        val structure2 = MutableStructure(map2)

        Assert.assertEquals(structure1, structure2)
    }
}