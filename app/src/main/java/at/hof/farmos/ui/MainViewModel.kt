package at.hof.farmos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.hof.farmos.FarmApp
import at.hof.farmos.data.BLUEH_FLAG
import at.hof.farmos.data.FieldWorkDraft
import at.hof.farmos.data.ListWork
import at.hof.farmos.data.LogDraft
import at.hof.farmos.data.db.FieldListEntity
import at.hof.farmos.data.db.LocationEntity
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
    val plantTypes = share(repo.plantTypes)
    val materials = share(repo.materials)
    val flags = share(repo.flags)
    val fieldLists = share(repo.fieldLists)
    val allListItems = share(repo.allListItems)
    fun fieldList(id: String) = repo.fieldList(id)
    fun listItems(listId: String) = repo.listItems(listId)
    val recentLogs = share(repo.recentLogs)
    val pendingOps = share(repo.pendingOps)

    fun animal(id: String) = repo.animal(id)
    fun location(id: String) = repo.location(id)
    fun logsFor(id: String) = repo.logsFor(id)


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
        safeLaunch("Abmelden") {
            repo.logout()
            _loggedIn.value = false
        }
    }

    // ---------------------------------------------------------------- Sync

    private val _lastError = MutableStateFlow(prefs.lastSyncError)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private fun rememberError(text: String?) {
        prefs.lastSyncError = text
        prefs.lastSyncErrorAt = if (text != null) System.currentTimeMillis() else 0L
        _lastError.value = text
    }

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
                    rememberError(r.error ?: "Server nicht erreichbar")
                    if (!quiet) _message.value = "Offline – Änderungen werden später übertragen"
                } else if (r.error != null) {
                    rememberError(r.error)
                    _message.value = r.error
                } else {
                    rememberError(null)
                    if (!quiet) {
                        _message.value = if (r.pushed > 0) "${r.pushed} Änderung(en) hochgeladen, Daten aktuell"
                        else "Daten aktuell"
                    }
                }
            } catch (e: NotLoggedInException) {
                _loggedIn.value = false
            } catch (e: Exception) {
                val text = Repository.describe(e)
                rememberError(text)
                _message.value = text
            } finally {
                _syncing.value = false
            }
        }
    }

    // ---------------------------------------------------------------- Verbindung testen

    private val _diag = MutableStateFlow<List<Repository.DiagItem>?>(null)
    val diag: StateFlow<List<Repository.DiagItem>?> = _diag.asStateFlow()
    private val _diagRunning = MutableStateFlow(false)
    val diagRunning: StateFlow<Boolean> = _diagRunning.asStateFlow()

    fun runDiagnose() {
        if (_diagRunning.value) return
        viewModelScope.launch {
            _diagRunning.value = true
            _diag.value = null
            try {
                _diag.value = repo.diagnose()
            } catch (e: Exception) {
                _diag.value = listOf(Repository.DiagItem("Test", false, Repository.describe(e)))
            } finally {
                _diagRunning.value = false
            }
        }
    }

    /** Startet eine Aktion; Fehler werden als Meldung angezeigt statt die App abstürzen zu lassen. */
    private fun safeLaunch(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("farmOSHof", what, e)
                _message.value = "$what fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun afterLocalChange(text: String) {
        _message.value = text
        SyncWorker.syncSoon(getApplication())
    }

    fun retryFailed() {
        safeLaunch("Erneut versuchen") { repo.retryFailed(); sync() }
    }

    fun discard(op: PendingOp) {
        safeLaunch("Verwerfen") { repo.discardOp(op) }
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
        safeLaunch("Tier speichern") {
            val tid = typeId ?: repo.createAnimalType(newTypeName.orEmpty().trim().ifBlank { "Tier" })
            repo.createAnimal(name.trim(), sex, birthdate, idTag, tid, typeName, notes)
            afterLocalChange("Tier „${name.trim()}“ gespeichert")
        }
    }

    fun createFieldWork(draft: FieldWorkDraft) {
        safeLaunch("${draft.work.label} speichern") {
            withContext(Dispatchers.IO) {
                draft.photos.forEach { path ->
                    PhotoStore.writeMeta(path, draft.timestamp, null, null, "${draft.work.label} ${draft.field?.name.orEmpty()}")
                }
            }
            repo.createFieldWork(draft)
            afterLocalChange("${draft.work.label} gespeichert")
        }
    }

    // ---------------------------------------------------------------- Feldlisten

    fun saveFieldList(id: String?, name: String, work: ListWork?, notes: String?, locationIds: List<String>, then: (String) -> Unit) {
        safeLaunch("Liste speichern") {
            val listId = repo.saveFieldList(id, name, work, notes, locationIds)
            _message.value = "Liste „${name.trim()}“ gespeichert"
            then(listId)
        }
    }

    fun createListFromLogs(name: String, work: ListWork?, doneAt: Map<String, Long>, then: (String) -> Unit) {
        safeLaunch("Liste erstellen") {
            val id = repo.createListFromLogs(name, work, doneAt)
            _message.value = "Liste mit ${doneAt.size} Feldern erstellt"
            then(id)
        }
    }

    fun setListItemDone(listId: String, locationId: String, done: Boolean) {
        safeLaunch("Abhaken") { repo.setListItemDone(listId, locationId, done) }
    }

    fun setAllDone(listId: String) {
        safeLaunch("Alle abhaken") { repo.setAllDone(listId) }
    }

    fun deleteFieldList(id: String) {
        safeLaunch("Liste löschen") { repo.deleteFieldList(id) }
    }

    fun logFieldList(list: FieldListEntity) {
        safeLaunch("In farmOS eintragen") {
            val n = repo.logFieldList(list)
            afterLocalChange(if (n > 0) "$n Feld(er) in farmOS eingetragen" else "Nichts einzutragen")
        }
    }

    /** Position einer Fläche setzen (null = in der App gesetzte Position löschen). */
    fun setPosition(location: LocationEntity, lat: Double?, lon: Double?) {
        safeLaunch("Position speichern") {
            repo.setPosition(location, lat, lon)
            afterLocalChange(if (lat != null) "Position von „${location.name}“ gespeichert" else "Gesetzte Position gelöscht")
        }
    }

    /** Fläche als Blühfläche markieren bzw. Markierung entfernen. */
    fun setBluehflaeche(location: LocationEntity, on: Boolean) {
        safeLaunch("Markierung") {
            repo.setFlag(location, BLUEH_FLAG, on)
            afterLocalChange(if (on) "„${location.name}“ als Blühfläche markiert" else "Blühflächen-Markierung entfernt")
        }
    }

    fun createLog(draft: LogDraft, exifDescription: String) {
        safeLaunch("Eintrag speichern") {
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

    // Muss am Ende stehen, damit alle Felder schon initialisiert sind.
    init {
        if (prefs.isLoggedIn) sync(quiet = true)
    }
}
