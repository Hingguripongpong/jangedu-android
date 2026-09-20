package com.example.janggiai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.janggiai.navigation.JanggiNavHost
import com.example.janggiai.ui.common.AppForeground
import com.example.janggiai.ui.theme.JanggiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as JanggiApplication).container
        setContent {
            val settings by container.settings.settings.collectAsState()
            JanggiTheme(mode = settings.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { JanggiNavHost() }
            }
        }
    }

    // The engine burns CPU: stop searches while the app is not visible, resume when it is.
    override fun onStart() { super.onStart(); AppForeground.set(true) }
    override fun onStop() { AppForeground.set(false); super.onStop() }
}
