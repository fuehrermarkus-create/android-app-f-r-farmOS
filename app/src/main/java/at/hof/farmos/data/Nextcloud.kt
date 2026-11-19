package at.hof.farmos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Legt Fotos zusätzlich in Nextcloud ab (WebDAV), damit man sie am PC als Ordner sieht.
 * Anmeldung mit Benutzername + App-Passwort (Nextcloud › Einstellungen › Sicherheit).
 */
class Nextcloud(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val configured: Boolean
        get() = prefs.ncUrl.isNotBlank() && prefs.ncUser.isNotBlank() && prefs.ncPassword.isNotBlank()

    private fun davUrl(segments: List<String>): HttpUrl {
        val base = FarmApi.normalizeUrl(prefs.ncUrl).toHttpUrlOrNull()
            ?: throw ApiException(400, "Nextcloud-Adresse ungültig")
        val b = base.newBuilder()
            .addPathSegment("remote.php").addPathSegment("dav").addPathSegment("files")
            .addPathSegment(prefs.ncUser)
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(prefs.ncUser, prefs.ncPassword, Charsets.UTF_8))

    private fun message(code: Int): String = when (code) {
        401 -> "Nextcloud: Benutzer oder App-Passwort falsch"
        403 -> "Nextcloud: kein Zugriff"
        404 -> "Nextcloud: Adresse falsch (nicht gefunden)"
        400 -> "Nextcloud: Adresse ist nicht als vertrauenswürdig eingetragen (trusted_domains)?"
        else -> "Nextcloud: HTTP $code"
    }

    /** Prüft Adresse und Zugangsdaten. */
    suspend fun test() = withContext(Dispatchers.IO) {
        val req = request(davUrl(emptyList())).header("Depth", "0").method("PROPFIND", null).build()
        client.newCall(req).execute().use { r -> if (!r.isSuccessful) throw ApiException(r.code, message(r.code)) }
    }

    /** Lädt eine Datei nach [remotePath] (z. B. "farmOS Fotos/Breite/2026-11-19 Foto.jpg") hoch. */
    suspend fun upload(remotePath: String, file: File) = withContext(Dispatchers.IO) {
        val segs = remotePath.split("/").filter { it.isNotBlank() }
        // Ordner anlegen (405 = gibt es schon)
        for (i in 1 until segs.size) {
            val req = request(davUrl(segs.take(i))).method("MKCOL", null).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful && r.code != 405) throw ApiException(r.code, message(r.code))
            }
        }
        val put = request(davUrl(segs)).put(file.asRequestBody("image/jpeg".toMediaType())).build()
        client.newCall(put).execute().use { r -> if (!r.isSuccessful) throw ApiException(r.code, message(r.code)) }
    }

    companion object {
        /** Zeichen, die in Datei-/Ordnernamen stören, ersetzen. */
        fun clean(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").trim().take(80).ifBlank { "Foto" }
    }
}
