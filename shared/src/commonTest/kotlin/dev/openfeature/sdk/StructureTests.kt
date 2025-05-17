@file:OptIn(ExperimentalTime::class)

package dev.openfeature.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
        // The structure should contain the same content as the input map, but not necessarily the same map reference
        assertEquals(map.toMap(), structure.asMap())

        // Verify that modifying the original map doesn't affect the structure
        map["key"] = Value.String("modified")
        assertEquals("test", structure.getValue("key")?.asString())
    }

    @Test
    fun testAddAndGetReturnValues() {
        val now = Clock.System.now()
        val structure = ImmutableStructure(
            mapOf(
                "string" to Value.String("val"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(13),
                "double" to Value.Double(0.5),
                "date" to Value.Instant(now),
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
        assertEquals(now, structure.getValue("date")?.asInstant())
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

        // Verify that modifying the original maps doesn't affect the structures
        map["key"] = Value.String("modified1")
        map2["key"] = Value.String("modified2")

        assertEquals(structure1, structure2)
        assertEquals("test", structure1.getValue("key")?.asString())
        assertEquals("test", structure2.getValue("key")?.asString())
    }
}