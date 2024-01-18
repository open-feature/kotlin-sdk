package dev.openfeature.sdk.kotlinxserialization

import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.OpenFeatureError
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

object ValueSerializer : JsonContentPolymorphicSerializer<Value>(Value::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.jsonObject.keys) {
        emptySet<String>() -> Value.Null.serializer()
        setOf("string") -> Value.String.serializer()
        setOf("boolean") -> Value.Boolean.serializer()
        setOf("integer") -> Value.Integer.serializer()
        setOf("double") -> Value.Double.serializer()
        setOf("date") -> Value.Date.serializer()
        setOf("list") -> Value.List.serializer()
        setOf("structure") -> Value.Structure.serializer()
        else -> throw OpenFeatureError.ParseError("couldn't find deserialization key for Value")
    }
}