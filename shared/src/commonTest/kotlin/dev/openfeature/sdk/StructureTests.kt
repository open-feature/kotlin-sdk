package dev.openfeature.sdk

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureTests {

    @Test
    fun testNoArgIsEmpty() {
        val structure = ImmutableContext()
        assertTrue(structure.asMap().keys.isEmpty())
    }

    @Test
    fun testArgShouldContainNewMap() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val structure = ImmutableStructure(map)

        assertEquals("test", structure.getValue("key")?.asString())
        assertEquals(map, structure.asMap())
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

        assertEquals(true, structure.getValue("bool")?.asBoolean())
        assertEquals("val", structure.getValue("string")?.asString())
        assertEquals(13, structure.getValue("int")?.asInteger())
        assertEquals(0.5, structure.getValue("double")?.asDouble())
        assertEquals(now, structure.getValue("date")?.asDate())
        assertEquals(listOf<Value>(), structure.getValue("list")?.asList())
        assertEquals(mapOf<String, Value>(), structure.getValue("structure")?.asStructure())
    }

    @Test
    fun testCompareStructure() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val map2: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val structure1 = ImmutableStructure(map)
        val structure2 = ImmutableStructure(map2)

        assertEquals(structure1, structure2)
    }
}