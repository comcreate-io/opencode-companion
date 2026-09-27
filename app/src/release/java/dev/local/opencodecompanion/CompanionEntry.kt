package dev.local.opencodecompanion

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.local.opencodecompanion.connected.ConnectedScreen
import dev.local.opencodecompanion.connected.ConnectedViewModel

@Composable
fun CompanionEntry(viewModel: ConnectedViewModel, modifier: Modifier = Modifier) {
    ConnectedScreen(viewModel, modifier)
}
