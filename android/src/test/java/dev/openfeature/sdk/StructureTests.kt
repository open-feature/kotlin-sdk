package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test
import java.util.Date

class StructureTests {

    @Test
    fun testNoArgIsEmpty() {
        val structure = ImmutableContext()
        Assert.assertTrue(structure.asMap().keys.isEmpty())
    }

    @Test
    fun testArgShouldContainNewMap() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val structure = ImmutableStructure(map)

        Assert.assertEquals("test", structure.getValue("key")?.asString())
        Assert.assertEquals(map, structure.asMap())
    }

    @Test
    fun testAddAndGetReturnValues() {
        val now = Date()
        val structure = ImmutableStructure(
            mapOf(
                "string" to Value.String("val"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(13),
                "double" to Value.Double(0.5),
                "date" to Value.Date(now),
                "list" to Value.List(listOf()),
                "structure" to Value.Structure(
                    mapOf()
                )
            )
        )

        Assert.assertEquals(true, structure.getValue("bool")?.asBoolean())
        Assert.assertEquals("val", structure.getValue("string")?.asString())
        Assert.assertEquals(13, structure.getValue("int")?.asInteger())
        Assert.assertEquals(0.5, structure.getValue("double")?.asDouble())
        Assert.assertEquals(now, structure.getValue("date")?.asDate())
        Assert.assertEquals(listOf<Value>(), structure.getValue("list")?.asList())
        Assert.assertEquals(mapOf<String, Value>(), structure.getValue("structure")?.asStructure())
    }

    @Test
    fun testCompareStructure() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val map2: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val structure1 = ImmutableStructure(map)
        val structure2 = ImmutableStructure(map2)

        Assert.assertEquals(structure1, structure2)
    }
}