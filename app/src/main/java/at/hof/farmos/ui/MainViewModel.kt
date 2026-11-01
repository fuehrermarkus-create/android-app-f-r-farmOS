package at.hof.farmos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.hof.farmos.FarmApp
import at.hof.farmos.data.LogDraft
import at.hof.farmos.photo.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import at.hof.farmos.data.NotLoggedInException
import at.hof.farmos.data.Repository
import at.hof.farmos.data.db.PendingOp
import at.hof.farmos.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FarmApp
    private val repo = app.repository
    val prefs = app.prefs

    private val _loggedIn = MutableStateFlow(prefs.isLoggedIn)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Kurze Meldung für die Snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastSync = MutableStateFlow(prefs.lastSync)
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    private fun <T> share(flow: kotlinx.coroutines.flow.Flow<List<T>>) =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val animals = share(repo.animals)
    val locations = share(repo.locations)
    val animalTypes = share(repo.animalTypes)
    val categories = share(repo.categories)
    val recentLogs = share(repo.recentLogs)
    val pendingOps = share(repo.pendingOps)

    fun animal(id: String) = repo.animal(id)
    fun location(id: String) = repo.location(id)
    fun logsFor(id: String) = repo.logsFor(id)

    init {
        if (prefs.isLoggedIn) sync(quiet = true)
    }

    fun consumeMessage() { _message.value = null }

    // ---------------------------------------------------------------- Anmeldung

    private val _loginBusy = MutableStateFlow(false)
    val loginBusy: StateFlow<Boolean> = _loginBusy.asStateFlow()
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    fun login(url: String, user: String, password: String) {
        viewModelScope.launch {
            _loginBusy.value = true
            _loginError.value = null
            try {
                app.api.login(url, user, password)
                _loggedIn.value = true
                sync()
            } catch (e: Exception) {
                _loginError.value = Repository.describe(e) + hint(e)
            } finally {
                _loginBusy.value = false
            }
        }
    }

    private fun hint(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("invalid_client") || m.contains("Client authentication failed") ->
                "\n\nTipp: In farmOS unter Verwaltung › Module das Modul „Default API Consumer“ " +
                    "aktivieren oder in den erweiterten Einstellungen die Client-ID anpassen."
            m.contains("invalid_scope") ->
                "\n\nTipp: Scope in den erweiterten Einstellungen prüfen (z. B. farm_manager)."
            else -> ""
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            _loggedIn.value = false
        }
    }

    // ---------------------------------------------------------------- Sync

    fun sync(quiet: Boolean = false) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val r = repo.sync()
                _lastSync.value = prefs.lastSync
                if (!prefs.isLoggedIn) {
                    _loggedIn.value = false
                    _message.value = "Sitzung abgelaufen – bitte neu anmelden"
                } else if (r.offline) {
                    if (!quiet) _message.value = "Offline – Änderungen werden später übertragen"
                } else if (r.error != null) {
                    _message.value = r.error
                } else if (!quiet) {
                    _message.value = if (r.pushed > 0) "${r.pushed} Änderung(en) hochgeladen, Daten aktuell"
                    else "Daten aktuell"
                }
            } catch (e: NotLoggedInException) {
                _loggedIn.value = false
            } catch (e: Exception) {
                _message.value = Repository.describe(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun afterLocalChange(text: String) {
        _message.value = text
        SyncWorker.syncSoon(getApplication())
    }

    fun retryFailed() {
        viewModelScope.launch { repo.retryFailed(); sync() }
    }

    fun discard(op: PendingOp) {
        viewModelScope.launch { repo.discardOp(op) }
    }

    // ---------------------------------------------------------------- Anlegen

    fun createAnimal(
        name: String,
        sex: String?,
        birthdate: LocalDate?,
        idTag: String?,
        typeId: String?,
        newTypeName: String?,
        typeName: String,
        notes: String?,
    ) {
        viewModelScope.launch {
            val tid = typeId ?: repo.createAnimalType(newTypeName!!.trim())
            repo.createAnimal(name.trim(), sex, birthdate, idTag, tid, typeName, notes)
            afterLocalChange("Tier „${name.trim()}“ gespeichert")
        }
    }

    fun createLog(draft: LogDraft, exifDescription: String) {
        viewModelScope.launch {
            // Endgültige Zeit, Position und Zuordnung in die Bilddateien schreiben
            withContext(Dispatchers.IO) {
                draft.photos.forEach { path ->
                    PhotoStore.writeMeta(path, draft.timestamp, draft.latitude, draft.longitude, exifDescription)
                }
            }
            repo.createLog(draft)
            afterLocalChange(
                if (draft.photos.isNotEmpty()) "${draft.photos.size} Foto(s) gespeichert" else "${draft.kind.label} gespeichert"
            )
        }
    }
}
