package de.hamzabistro.printstation.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.OrderRating
import de.hamzabistro.printstation.core.StaffOrder
import de.hamzabistro.printstation.core.Takings
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the history shows. */
data class HistoryState(
    val loading: Boolean = false,
    val orders: List<StaffOrder> = emptyList(),
    /** Null until read, and when reading it failed: the list is what matters. */
    val takings: Takings? = null,
    val error: String? = null,
    /** The orders a customer reported a problem with; empty when not known. */
    val reported: Set<String> = emptySet(),
    /** The customers' ratings, by order; empty when not known. */
    val ratings: Map<String, OrderRating> = emptyMap(),
)

/**
 * "Letzte Bestellungen": the recent orders whatever became of them, and what
 * today came to. Read when asked for, not polled — it is for answering a
 * call and cashing up, not for watching.
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph = (application as PrintStationApp).graph

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val read =
                attempt(app, { error -> _state.update { it.copy(error = error) } }) {
                    coroutineScope {
                        val takings = async { attempt(app, {}) { graph.history.takings(Instant.now()) } }
                        graph.history.recent() to takings.await()
                    }
                }
            // The "Reklamation" badges and the stars are footnotes: without them, the list still stands.
            val (reported, ratings) =
                read?.let { (orders, _) ->
                    val ids = orders.map { it.id }
                    coroutineScope {
                        val ratings = async { attempt(app, {}) { graph.ratings.forOrders(ids) } }
                        attempt(app, {}) { graph.issues.reported(ids) } to ratings.await()
                    }
                } ?: (null to null)
            _state.update { state ->
                if (read == null) state.copy(loading = false)
                else
                    state.copy(
                        loading = false,
                        orders = read.first,
                        takings = read.second,
                        reported = reported.orEmpty(),
                        ratings = ratings.orEmpty(),
                    )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(staff: StaffViewModel, onBack: () -> Unit) {
    val history: HistoryViewModel = viewModel()
    val state by history.state.collectAsStateWithLifecycle()
    // For the cards' clocks, the dishes' prep times and the map app this device uses.
    val staffState by staff.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = remember(staff) { Actions(context, staff) }
    LaunchedEffect(Unit) { history.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } },
                actions = { TextButton(onClick = history::load, enabled = !state.loading) { Text(stringResource(R.string.reload)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.takings?.let { takings ->
                item(key = "takings") {
                    Text(
                        pluralStringResource(R.plurals.history_takings, takings.count, takings.count, Format.euro(takings.total)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            state.error?.let { error ->
                item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(4.dp)) }
            }
            if (state.loading && state.orders.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (state.orders.isEmpty() && state.error == null) {
                item(key = "empty") {
                    Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
                }
            }
            items(state.orders, key = { it.id }) { order ->
                OrderCard(
                    order = order,
                    now = staffState.now,
                    prep = staffState.queue.prep,
                    prefs = staffState.prefs,
                    pending = null,
                    busy = false,
                    canPrint = false,
                    actions = actions,
                    readOnly = true,
                    reported = order.id in state.reported,
                    rating = state.ratings[order.id],
                )
            }
        }
    }
}
