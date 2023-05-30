package dev.openfeature.sdk

interface Features {
    fun getBooleanValue(key: String, defaultValue: Boolean): Boolean
    fun getBooleanValue(key: String, defaultValue: Boolean, options: FlagEvaluationOptions): Boolean
    fun getBooleanDetails(key: String, defaultValue: Boolean): FlagEvaluationDetails<Boolean>
    fun getBooleanDetails(key: String, defaultValue: Boolean, options: FlagEvaluationOptions): FlagEvaluationDetails<Boolean>
    fun getStringValue(key: String, defaultValue: String): String
    fun getStringValue(key: String, defaultValue: String, options: FlagEvaluationOptions): String
    fun getStringDetails(key: String, defaultValue: String): FlagEvaluationDetails<String>
    fun getStringDetails(key: String, defaultValue: String, options: FlagEvaluationOptions): FlagEvaluationDetails<String>
    fun getIntegerValue(key: String, defaultValue: Int): Int
    fun getIntegerValue(key: String, defaultValue: Int, options: FlagEvaluationOptions): Int
    fun getIntegerDetails(key: String, defaultValue: Int): FlagEvaluationDetails<Int>
    fun getIntegerDetails(key: String, defaultValue: Int, options: FlagEvaluationOptions): FlagEvaluationDetails<Int>
    fun getDoubleValue(key: String, defaultValue: Double): Double
    fun getDoubleValue(key: String, defaultValue: Double, options: FlagEvaluationOptions): Double
    fun getDoubleDetails(key: String, defaultValue: Double): FlagEvaluationDetails<Double>
    fun getDoubleDetails(key: String, defaultValue: Double, options: FlagEvaluationOptions): FlagEvaluationDetails<Double>
    fun getObjectValue(key: String, defaultValue: Value): Value
    fun getObjectValue(key: String, defaultValue: Value, options: FlagEvaluationOptions): Value
    fun getObjectDetails(key: String, defaultValue: Value): FlagEvaluationDetails<Value>
    fun getObjectDetails(key: String, defaultValue: Value, options: FlagEvaluationOptions): FlagEvaluationDetails<Value>
}
