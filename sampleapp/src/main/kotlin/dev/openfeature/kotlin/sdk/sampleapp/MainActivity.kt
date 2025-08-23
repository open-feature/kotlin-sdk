@file:OptIn(ExperimentalMaterial3Api::class)

package dev.openfeature.kotlin.sdk.sampleapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.openfeature.kotlin.sdk.FlagEvaluationDetails
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.multiprovider.MultiProvider
import dev.openfeature.kotlin.sdk.sampleapp.ui.theme.OpenFeatureTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val exampleProvider1 = ExampleProvider(
        "ExampleProvider1",
        flags = mapOf(
            "stringFlag" to "this is a string",
            "intFlag" to 1337,
            "doubleFlag" to 42.0
        )
    )
    private val exampleProvider2 = ExampleProvider(
        "ExampleProvider2",
        flags = mapOf(
            "booleanFlag" to true,
            "objectFlag" to Value.Structure(
                mapOf("key1" to Value.String("value"), "key2" to Value.Integer(10))
            )
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val multiProvider = MultiProvider(listOf(exampleProvider1, exampleProvider2))
        lifecycleScope.launch(Dispatchers.IO) {
            OpenFeatureAPI.setProviderAndWait(multiProvider)
            OpenFeatureAPI.statusFlow.collect {
                Log.i("OpenFeature", "Status: $it")
            }
        }

        val statusFlow = OpenFeatureAPI.statusFlow.map {
            if (it is OpenFeatureStatus.Error) {
                "Error: ${it.error.errorCode()} - ${it.error.message}"
            } else it.javaClass.simpleName
        }

        val multiProviderEventFlow = multiProvider.statusFlow.map {
            it.javaClass.simpleName
        }

        setContent {
            OpenFeatureTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainPage(
                        modifier = Modifier.padding(innerPadding),
                        setDelay = {
                            exampleProvider1.delayTime = it
                            exampleProvider2.delayTime = it
                        },
                        statusFlow = statusFlow,
                        multiProviderStatusFlow = multiProviderEventFlow,
                        toggleDefaults = {
                            exampleProvider1.returnDefaults = !exampleProvider1.returnDefaults
                            exampleProvider2.returnDefaults = !exampleProvider2.returnDefaults
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainPage(
    modifier: Modifier = Modifier,
    setDelay: (Long) -> Unit,
    statusFlow: Flow<String>,
    multiProviderStatusFlow: Flow<String>,
    defaultTab: Int = 0,
    toggleDefaults: () -> Unit
) {

    // Scrollview for the content
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)

    ) {
        Greeting()
        var selectedTabIndex by remember { mutableStateOf(defaultTab) }

        // Define the titles for the tabs
        val tabs = listOf("Status", "Evaluations", "Hooks")

        Column {
            // Tab Row
            TabRow(
                selectedTabIndex = selectedTabIndex
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            // Content for the currently selected tab
            when (selectedTabIndex) {
                0 -> ProviderAndStatus(
                    setDelay = setDelay,
                    statusFlow = statusFlow,
                    multiProviderStatusFlow = multiProviderStatusFlow,
                )
                1 -> Evaluations(toggleDefaults = toggleDefaults)
                2 -> Hooks()
            }
        }

    }
}

@Composable
fun Hooks() {
    Column(modifier = Modifier.fillMaxSize()) {
        Row {
            Text(text = "Hooks demo not yet implemented")
        }
    }
}

@Composable
fun Evaluations(toggleDefaults: () -> Unit) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()) // Enables vertical scrolling
    ) {
        Row(
            modifier = Modifier
                .padding(top = 20.dp)
                .fillMaxWidth()
        ) {
            var checked by remember {
                mutableStateOf(
                    (OpenFeatureAPI.getProvider() as? ExampleProvider)?.returnDefaults ?: false
                )
            }
            Text(
                text = "Evaluations",
                modifier = Modifier.align(Alignment.CenterVertically),
                style = MaterialTheme.typography.headlineMedium,
            )
            Checkbox(checked = checked, onCheckedChange = {
                checked = it
                toggleDefaults()
            }, modifier = Modifier.align(Alignment.CenterVertically))
            Text(
                text = "Provider return defaults",
                modifier = Modifier
                    .padding(start = 0.dp)
                    .align(Alignment.CenterVertically), // Space between checkbox and label
                style = MaterialTheme.typography.bodyMedium
            )

        }
        EvaluationRow("booleanFlag", false)
        EvaluationRow("stringFlag", "my default")
        EvaluationRow("doubleFlag", 0.0)
        EvaluationRow("intFlag", 99)
        EvaluationRow("objectFlag", Value.Null)
    }

}

@Composable
fun ProviderAndStatus(
    statusFlow: Flow<String>,
    multiProviderStatusFlow: Flow<String>,
    setDelay: (Long) -> Unit,
) {
    val statusState by statusFlow.collectAsState(initial = "initial")
    val multiProviderState by multiProviderStatusFlow.collectAsState(initial = "initial")

    val coroutineScope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        var sliderValue by remember { mutableStateOf(0.1f) }
        Text(
            text = "Provider delay: ${(sliderValue * 10000).toLong()}ms",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            style = MaterialTheme.typography.titleSmall
        )
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                setDelay((it * 10000).toLong())
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp) // Add spacing between buttons
        ) {
            Button(modifier = Modifier.weight(1f), onClick = {
                // Run setEvaluationContext without Wait to be able to spam the button
                OpenFeatureAPI.setEvaluationContext(ImmutableContext(randomString()))
            }) {
                Text("Set EvaluationContext", style = MaterialTheme.typography.bodySmall)
            }
            var loading by remember { mutableStateOf(false) }
            Button(modifier = Modifier.weight(1f), onClick = {
                coroutineScope.launch {
                    loading = true
                    OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext(randomString()))
                    loading = false
                }
            }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text("Set EvaluationContext & wait", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            style = MaterialTheme.typography.bodySmall,
            text = "EvaluationContext: ${
                OpenFeatureAPI.getEvaluationContext()?.asMap() ?: "null"
            }"
        )
        Text(
            style = MaterialTheme.typography.bodySmall,
            text = "TargetingKey: ${
                OpenFeatureAPI.getEvaluationContext()?.getTargetingKey() ?: "null"
            }"
        )
        Row(Modifier.padding(top = 8.dp)) {
            Text(text = "Current SDK status: $statusState")
        }

        Row(Modifier.padding(top = 8.dp)) {
            Text(text = "MultiProvider Latest event: $multiProviderState")
        }
    }
}


@Composable
fun <T : Any> EvaluationRow(flagName: String, defaultValue: T) {
    var flagValueEvaluationState by remember {
        mutableStateOf(FlagEvaluationDetails(flagName, defaultValue))
    }
    var flagNameState by remember { mutableStateOf(flagName) }
    Row {
        TextField(
            value = flagNameState,
            onValueChange = { flagNameState = it },
            modifier = Modifier.width(150.dp),
            textStyle = MaterialTheme.typography.bodyMedium, // Smaller text style
        )

        Button(onClick = {
            when (defaultValue) {
                is Boolean -> flagValueEvaluationState = OpenFeatureAPI.getClient()
                    .getBooleanDetails(flagNameState, defaultValue) as FlagEvaluationDetails<T>

                is String -> flagValueEvaluationState = OpenFeatureAPI.getClient()
                    .getStringDetails(flagNameState, defaultValue) as FlagEvaluationDetails<T>

                is Int -> flagValueEvaluationState = OpenFeatureAPI.getClient()
                    .getIntegerDetails(flagNameState, defaultValue) as FlagEvaluationDetails<T>

                is Double -> flagValueEvaluationState = OpenFeatureAPI.getClient()
                    .getDoubleDetails(flagNameState, defaultValue) as FlagEvaluationDetails<T>

                is Value -> flagValueEvaluationState = OpenFeatureAPI.getClient()
                    .getObjectDetails(flagNameState, defaultValue) as FlagEvaluationDetails<T>
            }

        }) {
            Text("Get", modifier = Modifier.align(Alignment.CenterVertically))
        }
        val v = if (defaultValue is Value) (flagValueEvaluationState.value as Value).asStructure()
            .toString() else flagValueEvaluationState.value.toString()
        Text(
            text = "value = $v", modifier = Modifier
                .padding(8.dp)
                .align(Alignment.CenterVertically)
        )

    }
    Row {
        Text(text = flagValueEvaluationState.toString(), style = MaterialTheme.typography.bodySmall)
    }
}

fun randomString(): String {
    Random(System.currentTimeMillis()).nextBytes(8).let {
        return it.joinToString("") { byte -> "%02x".format(byte) }
    }
}


@Composable
fun Greeting(modifier: Modifier = Modifier) {
    Text(
        style = MaterialTheme.typography.headlineLarge,
        text = "Welcome to OpenFeature!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun MainPagePreview() {
    OpenFeatureTheme {
        MainPage(
            setDelay = { },
            statusFlow = flowOf("initial"),
            multiProviderStatusFlow = flowOf("initial"),
            defaultTab = 0,
            toggleDefaults = { }
        )
    }
}