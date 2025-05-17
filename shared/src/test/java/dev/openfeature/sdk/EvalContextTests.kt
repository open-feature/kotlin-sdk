package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test
import java.util.Date

class EvalContextTests {

    @Test
    fun testContextIsImmutableButStoresTargetingKey() {
        val ctx = ImmutableContext()
        Assert.assertEquals("", ctx.getTargetingKey())

        val newCtx = ctx.withTargetingKey("test")
        Assert.assertEquals("test", newCtx.getTargetingKey())
    }

    @Test
    fun testContextStoresPrimitiveValues() {
        val now = Date()
        val ctx = ImmutableContext(
            attributes = mapOf(
                "string" to Value.String("value"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(3),
                "double" to Value.Double(3.14),
                "date" to Value.Date(now)
            )
        )

        Assert.assertEquals("value", ctx.getValue("string")?.asString())
        Assert.assertEquals(true, ctx.getValue("bool")?.asBoolean())
        Assert.assertEquals(3, ctx.getValue("int")?.asInteger())
        Assert.assertEquals(3.14, ctx.getValue("double")?.asDouble())
        Assert.assertEquals(now, ctx.getValue("date")?.asDate())
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
        Assert.assertEquals(3, ctx.getValue("list")?.asList()?.get(0)?.asInteger())
        Assert.assertEquals("4", ctx.getValue("list")?.asList()?.get(1)?.asString())
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
        Assert.assertEquals("test", ctx.getValue("struct")?.asStructure()?.get("string")?.asString())
        Assert.assertEquals(3, ctx.getValue("struct")?.asStructure()?.get("int")?.asInteger())
    }

    @Test
    fun testContextCanConvertToMap() {
        val now = Date()
        val ctx = ImmutableContext(
            attributes = mapOf(
                "str1" to Value.String("test1"),
                "str2" to Value.String("test2"),
                "bool1" to Value.Boolean(true),
                "bool2" to Value.Boolean(false),
                "int1" to Value.Integer(4),
                "int2" to Value.Integer(2),
                "double" to Value.Double(3.14),
                "dt" to Value.Date(now),
                "obj" to Value.Structure(mapOf("val1" to Value.Integer(1), "val2" to Value.String("2")))
            )
        )

        val map = ctx.asMap()
        val structure = map["obj"]?.asStructure()
        Assert.assertEquals("test1", map["str1"]?.asString())
        Assert.assertEquals("test2", map["str2"]?.asString())
        Assert.assertEquals(true, map["bool1"]?.asBoolean())
        Assert.assertEquals(false, map["bool2"]?.asBoolean())
        Assert.assertEquals(4, map["int1"]?.asInteger())
        Assert.assertEquals(2, map["int2"]?.asInteger())
        Assert.assertEquals(now, map["dt"]?.asDate())
        Assert.assertEquals(1, structure?.get("val1")?.asInteger())
        Assert.assertEquals("2", structure?.get("val2")?.asString())
    }

    @Test
    fun testContextHasUniqueKeyAcrossTypes() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "key" to Value.String("val1"),
                "key" to Value.Integer(3)
            )
        )
        Assert.assertNull(ctx.getValue("key")?.asString())
        Assert.assertEquals(3, ctx.getValue("key")?.asInteger())
    }

    @Test
    fun testContextStoresNull() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "null" to Value.Null
            )
        )
        Assert.assertEquals(true, ctx.getValue("null")?.isNull())
        Assert.assertNull(ctx.getValue("null")?.asString())
    }

    @Test
    fun testContextConvertsToObjectMap() {
        val key = "key1"
        val now = Date()
        val ctx = ImmutableContext(
            key,
            mapOf(
                "string" to Value.String("value"),
                "bool" to Value.Boolean(false),
                "integer" to Value.Integer(1),
                "double" to Value.Double(1.2),
                "date" to Value.Date(now),
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
        Assert.assertEquals(expected, ctx.asObjectMap())
    }

    @Test
    fun compareContexts() {
        val map: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val map2: MutableMap<String, Value> = mutableMapOf("key" to Value.String("test"))
        val ctx1 = ImmutableContext("user1", map)
        val ctx2 = ImmutableContext("user1", map2)

        Assert.assertEquals(ctx1, ctx2)
    }

    @Test
    fun testContextIsTrulyImmutable() {
        val mutableAttributes = mutableMapOf("key1" to Value.String("value1"), "key2" to Value.Integer(42))
        val context = ImmutableContext("targetingKey", mutableAttributes)

        // Verify initial state
        Assert.assertEquals("value1", context.getValue("key1")?.asString())
        Assert.assertEquals(42, context.getValue("key2")?.asInteger())

        // Modify the original mutable map
        mutableAttributes["key1"] = Value.String("modified")
        mutableAttributes["key3"] = Value.Boolean(true)

        // Verify that the context is not affected by the modifications
        Assert.assertEquals("value1", context.getValue("key1")?.asString())
        Assert.assertEquals(42, context.getValue("key2")?.asInteger())
        Assert.assertNull(context.getValue("key3"))
    }
}