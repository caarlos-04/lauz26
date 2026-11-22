package com.lauzhack.twin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.lauzhack.twin.ai.AndroidEnv

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // LLM settings baked into DEBUG builds only (empty in release => canned answers). Set before the chat is created.
        AndroidEnv.values = mapOf(
            "OPENAI_API_KEY" to BuildConfig.OPENAI_API_KEY,
            "ANTHROPIC_API_KEY" to BuildConfig.ANTHROPIC_API_KEY,
            "TWIN_LLM_MODEL" to BuildConfig.TWIN_LLM_MODEL,
        )

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}