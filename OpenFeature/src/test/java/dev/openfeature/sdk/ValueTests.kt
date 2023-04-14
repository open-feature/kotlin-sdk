package dev.openfeature.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert
import org.junit.Test
import java.time.Instant

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
        val value = Value.Structure(mapOf("field1" to Value.Integer(3), "field2" to Value.String("test")))
        Assert.assertEquals(value.asStructure(), mapOf("field1" to Value.Integer(3), "field2" to Value.String("test")))
    }

    @Test
    fun testEmptyListAllowed() {
        val value = Value.List(listOf())
        Assert.assertEquals(listOf<Value>(), value.asList())
    }

    @Test
    fun testEncodeDecode() {
        val date = Instant.parse("2023-03-01T14:01:46Z")
        val value = Value.Structure(
            mapOf(
                "null" to Value.Null,
                "text" to Value.String("test"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(3),
                "double" to Value.Double(4.5),
                "date" to Value.Instant(date),
                "list" to Value.List(listOf(Value.Boolean(false), Value.Integer(4))),
                "structure" to Value.Structure(mapOf("int" to Value.Integer(5)))
            )
        )

        val encodedValue = Json.encodeToJsonElement(value)
        val decodedValue = Json.decodeFromJsonElement<Value>(encodedValue)

        Assert.assertEquals(value, decodedValue)
    }

    @Test
    fun testJsonDecode() {
        val stringInstant = "2023-03-01T14:01:46Z"
        val json = "{" +
                "  \"structure\": {" +
                "    \"null\": {}," +
                "    \"text\": {" +
                "      \"string\": \"test\"" +
                "    }," +
                "    \"bool\": {" +
                "      \"boolean\": true" +
                "    }," +
                "    \"int\": {" +
                "      \"integer\": 3" +
                "    }," +
                "    \"double\": {" +
                "      \"double\": 4.5" +
                "    }," +
                "    \"date\": {" +
                "      \"instant\": \"$stringInstant\"" +
                "    }," +
                "    \"list\": {" +
                "      \"list\": [" +
                "        {" +
                "          \"boolean\": false" +
                "        }," +
                "        {" +
                "          \"integer\": 4" +
                "        }" +
                "      ]" +
                "    }," +
                "    \"structure\": {" +
                "      \"structure\": {" +
                "        \"int\": {" +
                "          \"integer\": 5" +
                "        }" +
                "      }" +
                "    }" +
                "  }" +
                "}"

        val expectedValue = Value.Structure(
            mapOf(
                "null" to Value.Null,
                "text" to Value.String("test"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(3),
                "double" to Value.Double(4.5),
                "date" to Value.Instant(Instant.parse(stringInstant)),
                "list" to Value.List(listOf(Value.Boolean(false), Value.Integer(4))),
                "structure" to Value.Structure(mapOf("int" to Value.Integer(5)))
            )
        )

        val decodedValue = Json.decodeFromString(Value.serializer(), json)
        Assert.assertEquals(expectedValue, decodedValue)
    }
}
