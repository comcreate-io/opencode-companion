package dev.local.opencodecompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import dev.local.opencodecompanion.connected.ConnectedViewModel

class MainActivity : ComponentActivity() {
    private val connected by lazy {
        ViewModelProvider(this, ConnectedViewModel.Factory(application))[
            ConnectedViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CompanionTheme { CompanionEntry(connected, Modifier.fillMaxSize()) } }
    }

    override fun onStart() {
        super.onStart()
        connected.foreground()
    }

    override fun onStop() {
        connected.background()
        super.onStop()
    }
}
