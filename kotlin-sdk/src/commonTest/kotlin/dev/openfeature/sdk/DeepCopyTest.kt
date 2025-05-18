package dev.openfeature.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepCopyTest {

    @Test
    fun testDeepCopyOfStructure() {
        val nestedMap = mutableMapOf<String, Value>()
        nestedMap["key1"] = Value.String("value1")

        val structure = Value.Structure(nestedMap)

        nestedMap["key1"] = Value.String("modified")
        nestedMap["key2"] = Value.Integer(42)

        assertEquals("value1", structure.structure["key1"]?.asString())
        assertNull(structure.structure["key2"])
    }

    @Test
    fun testDeepCopyOfList() {
        val nestedList = mutableListOf<Value>()
        nestedList.add(Value.String("item1"))

        val list = Value.List(nestedList)

        nestedList[0] = Value.String("modified")
        nestedList.add(Value.Integer(42))

        assertEquals("item1", list.list[0]?.asString())
        assertEquals(1, list.list.size)
    }

    @Test
    fun testDeepCopyOfNestedStructures() {
        val innerMap = mutableMapOf<String, Value>()
        innerMap["innerKey"] = Value.String("innerValue")

        val outerMap = mutableMapOf<String, Value>()
        outerMap["outerKey"] = Value.Structure(innerMap)

        val structure = Value.Structure(outerMap)

        innerMap["innerKey"] = Value.String("modified")
        innerMap["newKey"] = Value.Integer(123)

        val outerStructure = structure.structure["outerKey"]?.asStructure()
        assertEquals("innerValue", outerStructure?.get("innerKey")?.asString())
        assertNull(outerStructure?.get("newKey"))
    }
}