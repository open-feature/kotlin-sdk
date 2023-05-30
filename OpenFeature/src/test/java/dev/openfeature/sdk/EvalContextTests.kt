package dev.openfeature.sdk

import org.junit.Assert
import org.junit.Test
import java.time.Instant

class EvalContextTests {

    @Test
    fun testContextStoresTargetingKey() {
        val ctx = MutableContext()
        ctx.setTargetingKey("test")
        Assert.assertEquals("test", ctx.getTargetingKey())
    }

    @Test
    fun testContextStoresPrimitiveValues() {
        val ctx = MutableContext()
        val now = Instant.now()

        ctx.add("string", Value.String("value"))
        Assert.assertEquals("value", ctx.getValue("string")?.asString())
        ctx.add("bool", Value.Boolean(true))
        Assert.assertEquals(true, ctx.getValue("bool")?.asBoolean())
        ctx.add("int", Value.Integer(3))
        Assert.assertEquals(3, ctx.getValue("int")?.asInteger())
        ctx.add("double", Value.Double(3.14))
        Assert.assertEquals(3.14, ctx.getValue("double")?.asDouble())
        ctx.add("instant", Value.Instant(now))
        Assert.assertEquals(now, ctx.getValue("instant")?.asInstant())
    }

    @Test
    fun testContextStoresLists() {
        val ctx = MutableContext()

        ctx.add(
            "list",
            Value.List(
                listOf(
                    Value.Integer(3),
                    Value.String("4")
                )
            )
        )
        Assert.assertEquals(3, ctx.getValue("list")?.asList()?.get(0)?.asInteger())
        Assert.assertEquals("4", ctx.getValue("list")?.asList()?.get(1)?.asString())
    }

    @Test
    fun testContextStoresStructures() {
        val ctx = MutableContext()

        ctx.add(
            "struct",
            Value.Structure(
                mapOf(
                    "string" to Value.String("test"),
                    "int" to Value.Integer(3)
                )
            )
        )
        Assert.assertEquals("test", ctx.getValue("struct")?.asStructure()?.get("string")?.asString())
        Assert.assertEquals(3, ctx.getValue("struct")?.asStructure()?.get("int")?.asInteger())
    }

    @Test
    fun testContextCanConvertToMap() {
        val ctx = MutableContext()
        val now = Instant.now()
        ctx.add("str1", Value.String("test1"))
        ctx.add("str2", Value.String("test2"))
        ctx.add("bool1", Value.Boolean(true))
        ctx.add("bool2", Value.Boolean(false))
        ctx.add("int1", Value.Integer(4))
        ctx.add("int2", Value.Integer(2))
        ctx.add("dt", Value.Instant(now))
        ctx.add("obj", Value.Structure(mapOf("val1" to Value.Integer(1), "val2" to Value.String("2"))))

        val map = ctx.asMap()
        val structure = map["obj"]?.asStructure()
        Assert.assertEquals("test1", map["str1"]?.asString())
        Assert.assertEquals("test2", map["str2"]?.asString())
        Assert.assertEquals(true, map["bool1"]?.asBoolean())
        Assert.assertEquals(false, map["bool2"]?.asBoolean())
        Assert.assertEquals(4, map["int1"]?.asInteger())
        Assert.assertEquals(2, map["int2"]?.asInteger())
        Assert.assertEquals(now, map["dt"]?.asInstant())
        Assert.assertEquals(1, structure?.get("val1")?.asInteger())
        Assert.assertEquals("2", structure?.get("val2")?.asString())
    }

    @Test
    fun testContextHasUniqueKeyAcrossTypes() {
        val ctx = MutableContext()

        ctx.add("key", Value.String("val1"))
        ctx.add("key", Value.String("val2"))
        Assert.assertEquals("val2", ctx.getValue("key")?.asString())

        ctx.add("key", Value.Integer(3))
        Assert.assertNull(ctx.getValue("key")?.asString())
        Assert.assertEquals(3, ctx.getValue("key")?.asInteger())
    }

    @Test
    fun testContextCanChainAttributeAddition() {
        val ctx = MutableContext()

        val result =
            ctx.add("key1", Value.String("val1"))
        ctx.add("key2", Value.String("val2"))
        Assert.assertEquals("val1", result.getValue("key1")?.asString())
        Assert.assertEquals("val2", result.getValue("key2")?.asString())
    }

    @Test
    fun testContextCanAddNull() {
        val ctx = MutableContext()

        ctx.add("null", Value.Null)
        Assert.assertEquals(true, ctx.getValue("null")?.isNull())
        Assert.assertNull(ctx.getValue("null")?.asString())
    }

    @Test
    fun testContextConvertsToObjectMap() {
        val key = "key1"
        val now = Instant.now()
        val ctx = MutableContext(key)
        ctx.add("string", Value.String("value"))
        ctx.add("bool", Value.Boolean(false))
        ctx.add("integer", Value.Integer(1))
        ctx.add("double", Value.Double(1.2))
        ctx.add("date", Value.Instant(now))
        ctx.add("null", Value.Null)
        ctx.add("list", Value.List(listOf(Value.String("item1"), Value.Boolean(true))))
        ctx.add(
            "structure",
            Value.Structure(
                mapOf(
                    "field1" to Value.Integer(3),
                    "field2" to Value.Double(3.14)
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
        val ctx1 = MutableContext("user1", map)
        val ctx2 = MutableContext("user1", map2)

        Assert.assertEquals(ctx1, ctx2)
    }
}