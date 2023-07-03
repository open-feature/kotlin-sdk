package dev.openfeature.sdk

import android.annotation.SuppressLint
import dev.openfeature.sdk.exceptions.OpenFeatureError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

@Serializable(with = ValueSerializer::class)
sealed interface Value {

    fun asString(): kotlin.String? = if (this is String) string else null
    fun asBoolean(): kotlin.Boolean? = if (this is Boolean) boolean else null
    fun asInteger(): Int? = if (this is Integer) integer else null
    fun asDouble(): kotlin.Double? = if (this is Double) double else null
    fun asInstant(): Date? = if (this is Instant) instant else null
    fun asList(): kotlin.collections.List<Value>? = if (this is List) list else null
    fun asStructure(): Map<kotlin.String, Value>? = if (this is Structure) structure else null
    fun isNull(): kotlin.Boolean = this is Null

    @Serializable
    data class String(val string: kotlin.String) : Value

    @Serializable
    data class Boolean(val boolean: kotlin.Boolean) : Value

    @Serializable
    data class Integer(val integer: Int) : Value

    @Serializable
    data class Double(val double: kotlin.Double) : Value

    @Serializable
    data class Instant(@Serializable(DateSerializer::class) val instant: Date) : Value

    @Serializable
    data class Structure(val structure: Map<kotlin.String, Value>) : Value

    @Serializable
    data class List(val list: kotlin.collections.List<Value>) : Value

    @Serializable
    object Null : Value {
        override fun equals(other: Any?): kotlin.Boolean {
            return other is Null
        }
        override fun hashCode(): Int {
            return javaClass.hashCode()
        }
    }
}

object ValueSerializer : JsonContentPolymorphicSerializer<Value>(Value::class) {
    override fun selectDeserializer(element: JsonElement) = when (element.jsonObject.keys) {
        emptySet<String>() -> Value.Null.serializer()
        setOf("string") -> Value.String.serializer()
        setOf("boolean") -> Value.Boolean.serializer()
        setOf("integer") -> Value.Integer.serializer()
        setOf("double") -> Value.Double.serializer()
        setOf("instant") -> Value.Instant.serializer()
        setOf("list") -> Value.List.serializer()
        setOf("structure") -> Value.Structure.serializer()
        else -> throw OpenFeatureError.ParseError("couldn't find deserialization key for Value")
    }
}

object DateSerializer : KSerializer<Date> {
    @SuppressLint("SimpleDateFormat")
    private val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Date) = encoder.encodeString(df.format(value))
    override fun deserialize(decoder: Decoder): Date = try {
        df.parse(decoder.decodeString()) ?: throw IllegalArgumentException("unable to parse ${decoder.decodeString()}")
    } catch (e: Exception) {
        throw IllegalArgumentException(e)
    }
}