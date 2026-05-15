
package dev.openfeature.kotlin.sdk.e2e

import dev.openfeature.kotlin.sdk.Client
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.providers.memory.Flag
import dev.openfeature.kotlin.sdk.providers.memory.InMemoryProvider
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals

class EvaluationSteps {
    private lateinit var client: Client

    @Given("a stable provider")
    fun setup(): Unit = runBlocking {
        val flags = mapOf(
            "boolean-flag" to
                Flag.builder<Boolean>().variant("on", true)
                    .variant("off", false).defaultVariant("on").build(),
            "string-flag" to
                Flag.builder<String>().variant("greeting", "hi").defaultVariant("greeting").build(),
            "integer-flag" to Flag.builder<Int>().variant("ten", 10).defaultVariant("ten").build(),
            "float-flag" to Flag.builder<Double>().variant("half", 0.5).defaultVariant("half").build(),
            "object-flag" to Flag.builder<Value>().variant(
                "template",
                Value.Structure(
                    mapOf(
                        "showImages" to Value.Boolean(true),
                        "title" to Value.String("Check out these pics!"),
                        "imagesPerPage" to Value.Integer(100)
                    )
                )
            ).defaultVariant("template").build(),
            "context-aware" to Flag.builder<String>()
                .variant("internal", "INTERNAL")
                .variant("external", "EXTERNAL")
                .defaultVariant("external")
                .contextEvaluator { _, ctx ->
                    if (ctx?.getValue("customer")?.asBoolean() == false) "internal" else "external"
                }
                .build(),
            "wrong-flag" to Flag.builder<String>()
                .variant("one", "uno")
                .variant("two", "dos")
                .defaultVariant("one")
                .build()
        )
        val provider = InMemoryProvider(flags)
        OpenFeatureAPI.setProviderAndWait(provider)
        client = OpenFeatureAPI.getClient()
    }

    private var booleanFlagValue: Boolean = false
    private var stringFlagValue: String = ""
    private var intFlagValue: Int = 0
    private var doubleFlagValue: Double = 0.0
    private var objectFlagValue: Value? = null

    private var booleanFlagDetails: FlagEvaluationDetails<Boolean>? = null
    private var stringFlagDetails: FlagEvaluationDetails<String>? = null
    private var intFlagDetails: FlagEvaluationDetails<Int>? = null
    private var doubleFlagDetails: FlagEvaluationDetails<Double>? = null
    private var objectFlagDetails: FlagEvaluationDetails<Value>? = null

    private var contextAwareFlagKey: String = ""
    private var contextAwareDefaultValue: String = ""
    private var context: EvaluationContext? = null
    private var contextAwareValue: String = ""

    private var notFoundFlagKey: String = ""
    private var notFoundDefaultValue: String = ""
    private var notFoundDetails: FlagEvaluationDetails<String>? = null

    private var typeErrorFlagKey: String = ""
    private var typeErrorDefaultValue: Int = 0
    private var typeErrorDetails: FlagEvaluationDetails<Int>? = null

    @When("a boolean flag with key {string} is evaluated with default value {string}")
    fun evaluate_boolean(flagKey: String, defaultValue: String) {
        booleanFlagValue = client.getBooleanValue(flagKey, defaultValue.toBoolean())
    }

    @Then("the resolved boolean value should be {string}")
    fun assert_boolean_value(expected: String) {
        assertEquals(expected.toBoolean(), booleanFlagValue)
    }

    @When("a string flag with key {string} is evaluated with default value {string}")
    fun evaluate_string(flagKey: String, defaultValue: String) {
        stringFlagValue = client.getStringValue(flagKey, defaultValue)
    }

    @Then("the resolved string value should be {string}")
    fun assert_string_value(expected: String) {
        assertEquals(expected, stringFlagValue)
    }

    @When("an integer flag with key {string} is evaluated with default value {int}")
    fun evaluate_integer(flagKey: String, defaultValue: Int) {
        intFlagValue = client.getIntegerValue(flagKey, defaultValue)
    }

    @Then("the resolved integer value should be {int}")
    fun assert_integer_value(expected: Int) {
        assertEquals(expected, intFlagValue)
    }

    @When("a float flag with key {string} is evaluated with default value {double}")
    fun evaluate_double(flagKey: String, defaultValue: Double) {
        doubleFlagValue = client.getDoubleValue(flagKey, defaultValue)
    }

    @Then("the resolved float value should be {double}")
    fun assert_float_value(expected: Double) {
        assertEquals(expected, doubleFlagValue)
    }

    @When("an object flag with key {string} is evaluated with a null default value")
    fun evaluate_object(flagKey: String) {
        objectFlagValue = client.getObjectValue(flagKey, Value.Structure(emptyMap()))
    }

    @Then(
        "the resolved object value should be contain fields {string}, {string}, and {string}, " +
            "with values {string}, {string} and {int}, respectively"
    )
    fun assert_object_value(
        boolField: String,
        stringField: String,
        numberField: String,
        boolValue: String,
        stringValue: String,
        numberValue: Int
    ) {
        val structure = objectFlagValue?.asStructure()!!
        assertEquals(boolValue.toBoolean(), (structure[boolField] as Value.Boolean).boolean)
        assertEquals(stringValue, (structure[stringField] as Value.String).string)
        assertEquals(numberValue, (structure[numberField] as Value.Integer).integer)
    }

    @When("a boolean flag with key {string} is evaluated with details and default value {string}")
    fun evaluate_boolean_details(flagKey: String, defaultValue: String) {
        booleanFlagDetails = client.getBooleanDetails(flagKey, defaultValue.toBoolean())
    }

    @Then(
        "the resolved boolean details value should be {string}, " +
            "the variant should be {string}, and the reason should be {string}"
    )
    fun assert_boolean_details(expectedValue: String, expectedVariant: String, expectedReason: String) {
        assertEquals(expectedValue.toBoolean(), booleanFlagDetails?.value)
        assertEquals(expectedVariant, booleanFlagDetails?.variant)
        assertEquals(expectedReason, booleanFlagDetails?.reason)
    }

    @When("a string flag with key {string} is evaluated with details and default value {string}")
    fun evaluate_string_details(flagKey: String, defaultValue: String) {
        stringFlagDetails = client.getStringDetails(flagKey, defaultValue)
    }

    @Then(
        "the resolved string details value should be {string}, the variant should be {string}, " +
            "and the reason should be {string}"
    )
    fun assert_string_details(expectedValue: String, expectedVariant: String, expectedReason: String) {
        assertEquals(expectedValue, stringFlagDetails?.value)
        assertEquals(expectedVariant, stringFlagDetails?.variant)
        assertEquals(expectedReason, stringFlagDetails?.reason)
    }

    @When("an integer flag with key {string} is evaluated with details and default value {int}")
    fun evaluate_integer_details(flagKey: String, defaultValue: Int) {
        intFlagDetails = client.getIntegerDetails(flagKey, defaultValue)
    }

    @Then(
        "the resolved integer details value should be {int}, " +
            "the variant should be {string}, and the reason should be {string}"
    )
    fun assert_integer_details(expectedValue: Int, expectedVariant: String, expectedReason: String) {
        assertEquals(expectedValue, intFlagDetails?.value)
        assertEquals(expectedVariant, intFlagDetails?.variant)
        assertEquals(expectedReason, intFlagDetails?.reason)
    }

    @When("a float flag with key {string} is evaluated with details and default value {double}")
    fun evaluate_double_details(flagKey: String, defaultValue: Double) {
        doubleFlagDetails = client.getDoubleDetails(flagKey, defaultValue)
    }

    @Then(
        "the resolved float details value should be {double}, the variant should be {string}, " +
            "and the reason should be {string}"
    )
    fun assert_double_details(expectedValue: Double, expectedVariant: String, expectedReason: String) {
        assertEquals(expectedValue, doubleFlagDetails?.value)
        assertEquals(expectedVariant, doubleFlagDetails?.variant)
        assertEquals(expectedReason, doubleFlagDetails?.reason)
    }

    @When("an object flag with key {string} is evaluated with details and a null default value")
    fun evaluate_object_details(flagKey: String) {
        objectFlagDetails = client.getObjectDetails(flagKey, Value.Structure(emptyMap()))
    }

    @Then(
        "the resolved object details value should be contain fields {string}, {string}, and {string}, " +
            "with values {string}, {string} and {int}, respectively"
    )
    fun assert_object_details(
        boolField: String,
        stringField: String,
        numberField: String,
        boolValue: String,
        stringValue: String,
        numberValue: Int
    ) {
        val structure = objectFlagDetails?.value?.asStructure()!!
        assertEquals(boolValue.toBoolean(), (structure[boolField] as Value.Boolean).boolean)
        assertEquals(stringValue, (structure[stringField] as Value.String).string)
        assertEquals(numberValue, (structure[numberField] as Value.Integer).integer)
    }

    @Then("the variant should be {string}, and the reason should be {string}")
    fun assert_object_details_variant(expectedVariant: String, expectedReason: String) {
        assertEquals(expectedVariant, objectFlagDetails?.variant)
        assertEquals(expectedReason, objectFlagDetails?.reason)
    }

    @When(
        "context contains keys {string}, {string}, {string}, {string} with values {string}, {string}, {int}, {string}"
    )
    fun context_contains(
        field1: String,
        field2: String,
        field3: String,
        field4: String,
        value1: String,
        value2: String,
        value3: Int,
        value4: String
    ) {
        val attributes = mapOf(
            field1 to Value.String(value1),
            field2 to Value.String(value2),
            field3 to Value.Integer(value3),
            field4 to Value.Boolean(value4.toBoolean())
        )
        context = dev.openfeature.kotlin.sdk.ImmutableContext(attributes = attributes)
        runBlocking {
            OpenFeatureAPI.setEvaluationContextAndWait(context!!)
        }
    }

    @When("a flag with key {string} is evaluated with default value {string}")
    fun evaluate_context_aware(flagKey: String, defaultValue: String) {
        contextAwareFlagKey = flagKey
        contextAwareDefaultValue = defaultValue
        contextAwareValue = client.getStringValue(flagKey, defaultValue)
    }

    @Then("the resolved string response should be {string}")
    fun assert_context_aware_response(expected: String) {
        assertEquals(expected, contextAwareValue)
    }

    @Then("the resolved flag value is {string} when the context is empty")
    fun assert_context_empty_response(expected: String) {
        runBlocking {
            OpenFeatureAPI.setEvaluationContextAndWait(dev.openfeature.kotlin.sdk.ImmutableContext())
        }
        val emptyValue = client.getStringValue(contextAwareFlagKey, contextAwareDefaultValue)
        assertEquals(expected, emptyValue)
    }

    @When("a non-existent string flag with key {string} is evaluated with details and a fallback value {string}")
    fun evaluate_not_found(flagKey: String, defaultValue: String) {
        notFoundFlagKey = flagKey
        notFoundDefaultValue = defaultValue
        notFoundDetails = client.getStringDetails(flagKey, defaultValue)
    }

    @Then("the default string value should be returned")
    fun assert_not_found_value() {
        assertEquals(notFoundDefaultValue, notFoundDetails?.value)
    }

    @Then("the reason should indicate an error and the error code should indicate a missing flag with {string}")
    fun assert_not_found_reason(errorCode: String) {
        assertEquals(Reason.ERROR.toString(), notFoundDetails?.reason)
        assertEquals(errorCode, notFoundDetails?.errorCode?.name)
    }

    @When("a string flag with key {string} is evaluated as an integer, with details and a fallback value {int}")
    fun evaluate_type_mismatch(flagKey: String, defaultValue: Int) {
        typeErrorFlagKey = flagKey
        typeErrorDefaultValue = defaultValue
        typeErrorDetails = client.getIntegerDetails(flagKey, defaultValue)
    }

    @Then("the default integer value should be returned")
    fun assert_type_mismatch_value() {
        assertEquals(typeErrorDefaultValue, typeErrorDetails?.value)
    }

    @Then("the reason should indicate an error and the error code should indicate a type mismatch with {string}")
    fun assert_type_mismatch_reason(errorCode: String) {
        assertEquals(Reason.ERROR.toString(), typeErrorDetails?.reason)
        assertEquals(errorCode, typeErrorDetails?.errorCode?.name)
    }
}