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
import de.hamzabistro.printstation.station.Role
import de.hamzabistro.printstation.station.ShiftService
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

/** Everything the sign-in and the printer setup show. */
data class UiState(
    val account: Account? = null,
    /** What the account is to this app; null while it is being found out. */
    val role: Role? = null,
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

/**
 * Signing in and out, and the printer: the part of the app every account
 * has. Which account it is decides the rest — staff get the queue and the
 * alarm ([StaffViewModel]), a print account only this.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val graph: AppGraph = (application as PrintStationApp).graph
    private val scanner = PrinterScanner(application)
    private var scan: Job? = null

    private val _state =
        MutableStateFlow(
            UiState(
                account = graph.sessions.account.value,
                role = graph.settings.device.value.role,
                printer = graph.settings.printer,
                enabled = graph.settings.enabled,
                station = graph.stationState.value,
            )
        )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val captchaSiteKey: String = BuildConfig.TURNSTILE_SITE_KEY
    val captchaOrigin: String = BuildConfig.TURNSTILE_ORIGIN

    init {
        viewModelScope.launch {
            graph.sessions.account.collect { account ->
                _state.update { it.copy(account = account) }
                // Signed out by the server: what was the account's goes.
                if (account == null && graph.settings.device.value.role != null) graph.settings.forgetAccount()
            }
        }
        viewModelScope.launch { graph.settings.device.collect { d -> _state.update { it.copy(role = d.role) } } }
        viewModelScope.launch {
            graph.stationState.collect { station ->
                _state.update { it.copy(station = station, enabled = graph.settings.enabled) }
            }
        }
        // Signed in by an older version, which knew only print accounts:
        // find out once what this account is.
        if (graph.sessions.account.value != null && graph.settings.device.value.role == null) {
            viewModelScope.launch { learnRole() }
        }
        refresh()
    }

    private suspend fun learnRole() {
        try {
            val role = if (graph.staff.isStaff()) Role.STAFF else Role.PRINTER
            graph.settings.update { it.copy(role = role) }
        } catch (e: SignedOutException) {
            graph.settings.forgetAccount()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Offline: the printer setup is what this device did before,
            // and the role is asked again next time the app opens.
            graph.logger.warn("Could not find out whether this account is staff", e)
            _state.update { it.copy(role = Role.PRINTER) }
        }
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
            when {
                graph.staff.isStaff() -> {
                    graph.settings.update { it.copy(role = Role.STAFF) }
                    info(R.string.signed_in_staff)
                }
                graph.backend.canPrint() -> {
                    graph.settings.update { it.copy(role = Role.PRINTER, onShift = false) }
                    info(R.string.signed_in)
                }
                else -> {
                    // An account that may do nothing here is not kept on the device.
                    graph.sessions.signOut()
                    problem(R.string.problem_not_allowed_any)
                }
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

    /**
     * Ends the shift and printing here, takes this device off both lists
     * on the site, and ends the session on the server.
     */
    fun signOut() = act {
        if (graph.settings.device.value.onShift) {
            graph.settings.update { it.copy(onShift = false) }
            runCatching { graph.staff.off(graph.settings.stationId) }
        }
        switchOff()
        graph.sessions.signOut()
        graph.settings.forgetAccount()
        ShiftService.update(app)
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
        // A running station lets go of the old printer and takes this one.
        if (graph.settings.enabled) ShiftService.update(app, restartPrinting = true)
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
            ShiftService.update(app, skipWaiting = true)
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
        if (!graph.settings.enabled) return
        graph.settings.enabled = false
        _state.update { it.copy(enabled = false) }
        ShiftService.update(app)
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
