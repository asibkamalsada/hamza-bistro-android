package de.hamzabistro.printstation.ui

import android.app.Application
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hamzabistro.printstation.AppGraph
import de.hamzabistro.printstation.BuildConfig
import de.hamzabistro.printstation.PrintStationApp
import de.hamzabistro.printstation.R
import de.hamzabistro.printstation.core.Account
import de.hamzabistro.printstation.core.AuthRejectedException
import de.hamzabistro.printstation.core.NotAllowedException
import de.hamzabistro.printstation.core.PrinterException
import de.hamzabistro.printstation.core.SignedOutException
import de.hamzabistro.printstation.printer.FoundPrinter
import de.hamzabistro.printstation.printer.PrinterScanner
import de.hamzabistro.printstation.station.ChosenPrinter
import de.hamzabistro.printstation.station.PrintStationService
import de.hamzabistro.printstation.station.StationState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Everything the setup screen shows. */
data class UiState(
    val account: Account? = null,
    val printer: ChosenPrinter? = null,
    val enabled: Boolean = false,
    val station: StationState = StationState.Stopped,
    val scanning: Boolean = false,
    val found: List<FoundPrinter> = emptyList(),
    val busy: Boolean = false,
    val message: Message? = null,
    val batteryOptimised: Boolean = true,
    /** Bumped after every sign-in attempt: a captcha token is good for one. */
    val captchaRound: Int = 0,
)

data class Message(val text: String, val error: Boolean)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph: AppGraph = (application as PrintStationApp).graph
    private val scanner = PrinterScanner(application)
    private var scan: Job? = null

    private val _state =
        MutableStateFlow(
            UiState(
                account = graph.sessions.account.value,
                printer = graph.settings.printer,
                enabled = graph.settings.enabled,
                station = graph.stationState.value,
            )
        )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val captchaSiteKey: String = BuildConfig.TURNSTILE_SITE_KEY
    val captchaOrigin: String = BuildConfig.TURNSTILE_ORIGIN

    init {
        viewModelScope.launch { graph.sessions.account.collect { a -> _state.update { it.copy(account = a) } } }
        viewModelScope.launch {
            graph.stationState.collect { station ->
                _state.update { it.copy(station = station, enabled = graph.settings.enabled) }
            }
        }
        refresh()
    }

    /** Things that change outside the app: the battery setting, a permission. */
    fun refresh() {
        val power = app.getSystemService(PowerManager::class.java)
        _state.update {
            it.copy(
                batteryOptimised = !power.isIgnoringBatteryOptimizations(app.packageName),
                printer = graph.settings.printer,
                enabled = graph.settings.enabled,
            )
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun signIn(email: String, password: String, captchaToken: String?) = act {
        try {
            graph.sessions.signIn(email, password, captchaToken)
            // An account that may not print is not kept on the device.
            if (!graph.backend.canPrint()) {
                graph.sessions.signOut()
                problem(R.string.problem_not_allowed)
            } else {
                info(R.string.signed_in)
            }
        } catch (e: AuthRejectedException) {
            problem(
                when (e.code) {
                    "invalid_credentials", "invalid_grant" -> R.string.sign_in_wrong
                    "email_not_confirmed" -> R.string.sign_in_unconfirmed
                    "captcha_failed" -> R.string.sign_in_captcha
                    else -> R.string.sign_in_refused
                },
                e.message,
            )
        } finally {
            _state.update { it.copy(captchaRound = it.captchaRound + 1) }
        }
    }

    fun signOut() = act {
        switchOff()
        graph.sessions.signOut()
        info(R.string.signed_out)
    }

    fun scan() {
        scan?.cancel()
        scan =
            viewModelScope.launch {
                _state.update { it.copy(scanning = true, found = emptyList()) }
                try {
                    withTimeoutOrNull(SCAN_TIME) { scanner.scan().collect { list -> _state.update { it.copy(found = list) } } }
                } catch (e: PrinterException) {
                    problem(R.string.printer_failed, e.message)
                } finally {
                    _state.update { it.copy(scanning = false) }
                }
            }
    }

    fun choose(found: FoundPrinter) {
        scan?.cancel()
        graph.settings.choosePrinter(ChosenPrinter(found.address, found.name))
        _state.update { it.copy(printer = graph.settings.printer, found = emptyList()) }
        // A running station picks the new printer up when it starts again.
        if (graph.settings.enabled) {
            PrintStationService.stop(app)
            PrintStationService.start(app, skipWaiting = false)
        }
    }

    fun testPrint() = act {
        val printer = graph.settings.printer ?: return@act
        val ticket = graph.backend.testTicket(AppGraph.TICKET_LANG)
        withTimeoutOrNull(TICKET_TIMEOUT) { graph.printer(printer.address).print(ticket) }
            ?: throw PrinterException("the printer did not answer")
        info(R.string.printer_sent)
    }

    fun setEnabled(on: Boolean) = act {
        if (on) {
            graph.settings.enabled = true
            _state.update { it.copy(enabled = true) }
            PrintStationService.start(app, skipWaiting = true)
        } else {
            switchOff()
        }
    }

    /**
     * Stops printing here and takes the station off the list under
     * "Druckstationen" — the database's print_station_off(). Offline, it
     * stays listed until somebody removes it there.
     */
    private suspend fun switchOff() {
        graph.settings.enabled = false
        _state.update { it.copy(enabled = false) }
        PrintStationService.stop(app)
        try {
            graph.backend.off(graph.settings.stationId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            problem(R.string.station_off_failed, e.message)
        }
    }

    /** One action at a time, with its failure said on the screen. */
    private fun act(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignedOutException) {
                problem(R.string.stopped_signed_out)
            } catch (e: NotAllowedException) {
                problem(R.string.problem_not_allowed)
            } catch (e: PrinterException) {
                problem(R.string.printer_failed, e.message)
            } catch (e: Exception) {
                problem(R.string.problem_offline, e.message ?: e.javaClass.simpleName)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun info(@StringRes text: Int, vararg args: Any?) {
        _state.update { it.copy(message = Message(app.getString(text, *args), error = false)) }
    }

    private fun problem(@StringRes text: Int, vararg args: Any?) {
        _state.update { it.copy(message = Message(app.getString(text, *args), error = true)) }
    }

    private companion object {
        val SCAN_TIME = 15.seconds
        val TICKET_TIMEOUT = 20.seconds
    }
}
