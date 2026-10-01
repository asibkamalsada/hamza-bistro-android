package de.hamzabistro.printstation.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hamzabistro.printstation.station.Role
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val staff: StaffViewModel by viewModels()

    /** The order a tapped notification asked for. */
    private val focus = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A password is typed here, and customers' names and addresses are
        // shown: no screenshots, no picture in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Taps that arrive through another app's overlay are dropped, so an
        // overlay cannot trick somebody into accepting or switching off.
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        take(intent)
        setContent {
            AppTheme {
                val state by main.state.collectAsStateWithLifecycle()
                when {
                    state.account == null || state.role == Role.PRINTER -> MainScreen(main)
                    state.role == Role.STAFF -> StaffApp(staff, main, focus, onFocused = { focus.value = null })
                    // Finding out what this account is.
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ORDER)?.let { focus.value = it }
    }

    companion object {
        /** The order a notification is about, to scroll to. */
        const val EXTRA_ORDER = "order"
    }
}
