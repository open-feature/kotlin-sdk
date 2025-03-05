@file:OptIn(ExperimentalMaterial3Api::class)

package dev.openfeature.sdk.sampleapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.sampleapp.ui.theme.OpenFeatureTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            OpenFeatureAPI.setProviderAndWait(RemoteControlExampleProvider())
        }
        setContent {
            OpenFeatureTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainPage(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
@Composable
fun MainPage(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Greeting()
        Row {
            Text(text = "Current status")
        }

    }

}


@Composable
fun Greeting(modifier: Modifier = Modifier) {
    Text(
        text = "Welcome to OpenFeature!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun MainPagePreview() {
    OpenFeatureTheme {
        MainPage()
    }
}