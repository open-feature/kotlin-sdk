@file:OptIn(ExperimentalTime::class)

package dev.openfeature.kotlin.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class EvalContextTests {

    @Test
    fun testContextIsImmutableButStoresTargetingKey() {
        val ctx = ImmutableContext()
        assertEquals("", ctx.getTargetingKey())

        val newCtx = ctx.withTargetingKey("test")
        assertEquals("test", newCtx.getTargetingKey())
    }

    @Test
    fun testContextStoresPrimitiveValues() {
        val now = Clock.System.now()
        val ctx = ImmutableContext(
            attributes = mapOf(
                "string" to Value.String("value"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(3),
                "double" to Value.Double(3.14),
                "date" to Value.Instant(now)
            )
        )

        assertEquals("value", ctx.getValue("string")?.asString())
        assertEquals(true, ctx.getValue("bool")?.asBoolean())
        assertEquals(3, ctx.getValue("int")?.asInteger())
        assertEquals(3.14, ctx.getValue("double")?.asDouble())
        assertEquals(now, ctx.getValue("date")?.asInstant())
    }

    @Test
    fun testContextStoresLists() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "list" to
                    Value.List(
                        listOf(
                            Value.Integer(3),
                            Value.String("4")
                        )
                    )
            )
        )
        assertEquals(3, ctx.getValue("list")?.asList()?.get(0)?.asInteger())
        assertEquals("4", ctx.getValue("list")?.asList()?.get(1)?.asString())
    }

    @Test
    fun testContextStoresStructures() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "struct" to
                    Value.Structure(
                        mapOf(
                            "string" to Value.String("test"),
                            "int" to Value.Integer(3)
                        )
                    )
            )
        )
        assertEquals("test", ctx.getValue("struct")?.asStructure()?.get("string")?.asString())
        assertEquals(3, ctx.getValue("struct")?.asStructure()?.get("int")?.asInteger())
    }

    @Test
    fun testContextCanConvertToMap() {
        val now = Clock.System.now()
        val ctx = ImmutableContext(
            attributes = mapOf(
                "str1" to Value.String("test1"),
                "str2" to Value.String("test2"),
                "bool1" to Value.Boolean(true),
                "bool2" to Value.Boolean(false),
                "int1" to Value.Integer(4),
                "int2" to Value.Integer(2),
                "double" to Value.Double(3.14),
                "dt" to Value.Instant(now),
                "obj" to Value.Structure(mapOf("val1" to Value.Integer(1), "val2" to Value.String("2")))
            )
        )

        val map = ctx.asMap()
        val structure = map["obj"]?.asStructure()
        assertEquals("test1", map["str1"]?.asString())
        assertEquals("test2", map["str2"]?.asString())
        assertEquals(true, map["bool1"]?.asBoolean())
        assertEquals(false, map["bool2"]?.asBoolean())
        assertEquals(4, map["int1"]?.asInteger())
        assertEquals(2, map["int2"]?.asInteger())
        assertEquals(now, map["dt"]?.asInstant())
        assertEquals(1, structure?.get("val1")?.asInteger())
        assertEquals("2", structure?.get("val2")?.asString())
    }

    @Test
    fun testContextHasUniqueKeyAcrossTypes() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "key" to Value.String("val1"),
                "key" to Value.Integer(3)
            )
        )
        assertNull(ctx.getValue("key")?.asString())
        assertEquals(3, ctx.getValue("key")?.asInteger())
    }

    @Test
    fun testContextStoresNull() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "null" to Value.Null
            )
        )
        assertEquals(true, ctx.getValue("null")?.isNull())
        assertNull(ctx.getValue("null")?.asString())
    }

    @Test
    fun testContextConvertsToObjectMap() {
        val key = "key1"
        val now = Clock.System.now()
        val ctx = ImmutableContext(
            key,
            mapOf(
                "string" to Value.String("value"),
                "bool" to Value.Boolean(false),
                "integer" to Value.Integer(1),
                "double" to Value.Double(1.2),
                "date" to Value.Instant(now),
                "null" to Value.Null,
                "list" to Value.List(listOf(Value.String("item1"), Value.Boolean(true))),
                "structure" to Value.Structure(
                    mapOf(
                        "field1" to Value.Integer(3),
                        "field2" to Value.Double(3.14)
                    )
                )
            )
        )

        val expected = mapOf(
            "string" to "value",
            "bool" to false,
            "integer" to 1,
            "double" to 1.2,
            "date" to now,
            "null" to null,
            "list" to listOf("item1", true),
            "structure" to mapOf("field1" to 3, "field2" to 3.14)
        )
        assertEquals(expected, ctx.asObjectMap())
    }

    @Test
    fun compareContexts() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val map2: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val ctx1 = ImmutableContext("user1", map)
        val ctx2 = ImmutableContext("user1", map2)

        assertEquals(ctx1, ctx2)
    }

    @Test
    fun testContextIsTrulyImmutable() {
        val mutableAttributes = mutableMapOf("key1" to Value.String("value1"), "key2" to Value.Integer(42))
        val context = ImmutableContext("targetingKey", mutableAttributes)

        // Verify initial state
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
}