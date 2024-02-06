package dev.openfeature.sdk.kotlinxserialization

import android.annotation.SuppressLint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

@SuppressLint("SimpleDateFormat")
object DateSerializer : KSerializer<Date> {
    private val dateFormatter =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val fallbackDateFormatter =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Date) = encoder.encodeString(dateFormatter.format(value))
    override fun deserialize(decoder: Decoder): Date = with(decoder.decodeString()) {
        try {
            dateFormatter.parse(this)
                ?: throw IllegalArgumentException("unable to parse $this")
        } catch (e: Exception) {
            fallbackDateFormatter.parse(this)
                ?: throw IllegalArgumentException("unable to parse $this")
        }
    }
}