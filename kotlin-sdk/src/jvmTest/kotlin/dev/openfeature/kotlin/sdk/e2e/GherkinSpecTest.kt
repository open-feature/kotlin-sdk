package dev.openfeature.kotlin.sdk.e2e

import io.cucumber.junit.platform.engine.Constants.FILTER_NAME_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.ExcludeTags
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectDirectories
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectDirectories("../spec/specification/assets/gherkin")
/**
 * tells the JUnit platform to parse all the Gherkin files in the submodules but strictly run only
 * the scenarios originating under the feature named exact match Flag evaluation (which uniquely
 * targets evaluation.feature and ignores evaluation_v2.feature and the rest)
 */
@ConfigurationParameter(key = FILTER_NAME_PROPERTY_NAME, value = "^Flag evaluation\$")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.openfeature.kotlin.sdk.e2e")
@ExcludeTags("reason-codes-cached", "async", "immutability", "evaluation-options", "providers")
class GherkinSpecTest