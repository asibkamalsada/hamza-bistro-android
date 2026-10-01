package de.hamzabistro.printstation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow

/** A staff account's app: the queue, and its settings behind one button. */
@Composable
fun StaffApp(staff: StaffViewModel, main: MainViewModel, focus: StateFlow<String?>, onFocused: () -> Unit) {
    var settings by rememberSaveable { mutableStateOf(false) }
    if (settings) {
        SettingsScreen(staff, main, onBack = { settings = false })
    } else {
        QueueScreen(staff, focus, onFocused, onSettings = { settings = true })
    }
}
