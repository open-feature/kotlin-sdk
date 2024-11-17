package dev.openfeature.sdk

class EvaluationMetadata internal constructor(private val values: Map<String, Any>) {

    fun getString(key: String): String? = values[key] as? String

    fun getBoolean(key: String): Boolean? = values[key] as? Boolean

    fun getInt(key: String): Int? = values[key] as? Int

    fun getDouble(key: String): Double? = values[key] as? Double

    fun getAny(key: String): Any? = values[key]

    companion object {
        fun builder(): Builder {
            return Builder()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EvaluationMetadata

        return values == other.values
    }

    override fun hashCode(): Int {
        return values.hashCode()
    }
}

class Builder {
    private val values: MutableMap<String, Any> = mutableMapOf()

    fun putString(key: String, value: String): Builder {
        values[key] = value
        return this
    }

    fun putInt(key: String, value: Int): Builder {
        values[key] = value
        return this
    }

    fun putDouble(key: String, value: Double): Builder {
        values[key] = value
        return this
    }

    fun putBoolean(key: String, value: Boolean): Builder {
        values[key] = value
        return this
    }

    fun build(): EvaluationMetadata {
        return EvaluationMetadata(values.toMap())
    }
}