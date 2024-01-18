package dev.openfeature.sdk.kotlinxserialization

import dev.openfeature.sdk.Value
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert
import org.junit.Test
import java.time.Instant
import java.util.Date

class ValueSerializationTests {

    @Test
    fun testEncodeDecode() {
        val date = Date.from(Instant.parse("2023-03-01T14:01:46Z"))
        val value = Value.Structure(
            mapOf(
                "null" to Value.Null,
                "text" to Value.String("test"),
                "bool" to Value.Boolean(true),
                "int" to Value.Integer(3),
                "double" to Value.Double(4.5),
                "date" to Value.Date(date),
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
        val stringDateTime = "2023-03-01T14:01:46Z"
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
            "      \"date\": \"$stringDateTime\"" +
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
                "date" to Value.Date(Date.from(Instant.parse(stringDateTime))),
                "list" to Value.List(listOf(Value.Boolean(false), Value.Integer(4))),
                "structure" to Value.Structure(mapOf("int" to Value.Integer(5)))
            )
        )

        val decodedValue = Json.decodeFromString(Value.serializer(), json)
        Assert.assertEquals(expectedValue, decodedValue)
    }
}