package at.hof.farmos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fehler, den der Server mit einem HTTP-Status beantwortet hat. */
class ApiException(val code: Int, message: String) : Exception(message) {
    /** 4xx (außer 401/408/429) bedeutet: Wiederholen hilft nicht. */
    val isPermanent: Boolean get() = code in 400..499 && code != 401 && code != 408 && code != 429
}

class NotLoggedInException : Exception("Nicht angemeldet")

/** Ergebnis einer Listenabfrage inkl. mitgeladener Objekte (include=...). */
class ApiList(val data: List<JsonObject>, val included: Map<String, JsonObject>)

/**
 * Zugriff auf die farmOS-API (JSON:API unter /api, OAuth2 unter /oauth/token).
 */
class FarmApi(private val prefs: Prefs) {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()
    private val jsonApiType = "application/vnd.api+json".toMediaType()

    // ---------------------------------------------------------------- Anmeldung

    suspend fun login(serverUrl: String, username: String, password: String) {
        val base = normalizeUrl(serverUrl)
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", prefs.clientId)
            .add("scope", prefs.scope)
            .add("username", username)
            .add("password", password)
            .build()
        val tokens = tokenRequest(base, form)
        prefs.serverUrl = base
        prefs.username = username
        storeTokens(tokens)
    }

    private suspend fun refresh() {
        val rt = prefs.refreshToken ?: throw NotLoggedInException()
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", prefs.clientId)
            .add("refresh_token", rt)
            .build()
        try {
            storeTokens(tokenRequest(prefs.serverUrl, form))
        } catch (e: ApiException) {
            if (e.code == 400 || e.code == 401) {
                // Refresh-Token abgelaufen oder widerrufen -> neu anmelden.
                prefs.logout()
                throw NotLoggedInException()
            }
            throw e
        }
    }

    private suspend fun tokenRequest(base: String, form: FormBody): JsonObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$base/oauth/token").post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        val o = json.parseToJsonElement(body).jsonObject
                        (o.str("error_description") ?: o.str("message") ?: o.str("error"))
                    }.getOrNull()
                    throw ApiException(resp.code, msg ?: "Anmeldung fehlgeschlagen (HTTP ${resp.code})")
                }
                json.parseToJsonElement(body).jsonObject
            }
        }

    private fun storeTokens(o: JsonObject) {
        prefs.accessToken = o.str("access_token")
        o.str("refresh_token")?.let { prefs.refreshToken = it }
        val expiresIn = o.str("expires_in")?.toLongOrNull() ?: 3600L
        prefs.tokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000
    }

    suspend fun validToken(): String = tokenMutex.withLock {
        if (prefs.refreshToken == null) throw NotLoggedInException()
        val at = prefs.accessToken
        if (at == null || System.currentTimeMillis() >= prefs.tokenExpiry) {
            refresh()
        }
        prefs.accessToken ?: throw NotLoggedInException()
    }

    // ---------------------------------------------------------------- Anfragen

    /** Führt eine Anfrage aus, erneuert bei 401 einmal das Token. */
    private suspend fun call(
        method: String,
        urlOrPath: String,
        body: String?,
        file: File? = null,
    ): JsonObject? {
        var attempt = 0
        while (true) {
            val token = validToken()
            val url = if (urlOrPath.startsWith("http")) urlOrPath else prefs.serverUrl + urlOrPath
            val result = withContext(Dispatchers.IO) {
                val builder = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.api+json")
                val rb = body?.toRequestBody(jsonApiType)
                when (method) {
                    "UPLOAD" -> {
                        val f = file ?: throw IllegalArgumentException("Datei fehlt")
                        builder.header("Content-Disposition", "file; filename=\"${f.name}\"")
                        builder.post(f.asRequestBody("application/octet-stream".toMediaType()))
                    }
                    "GET" -> builder.get()
                    "POST" -> builder.post(rb ?: "".toRequestBody(jsonApiType))
                    "PATCH" -> builder.patch(rb ?: "".toRequestBody(jsonApiType))
                    "DELETE" -> builder.delete()
                    else -> throw IllegalArgumentException(method)
                }
                client.newCall(builder.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Triple(resp.code, resp.isSuccessful, text)
                }
            }
            val (code, ok, text) = result
            if (code == 401 && attempt == 0) {
                attempt++
                tokenMutex.withLock { prefs.tokenExpiry = 0 }
                continue
            }
            if (!ok) throw ApiException(code, errorMessage(code, text))
            if (text.isBlank()) return null
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun errorMessage(code: Int, text: String): String {
        val detail = runCatching {
            val errors = json.parseToJsonElement(text).jsonObject["errors"] as? JsonArray
            errors?.firstOrNull()?.jsonObject?.let { it.str("detail") ?: it.str("title") }
        }.getOrNull()
        return "HTTP $code" + (detail?.let { ": $it" } ?: "")
    }

    /** Lädt alle Seiten einer Ressource (folgt links.next). */
    suspend fun getAll(path: String, maxPages: Int = 40): ApiList {
        val out = mutableListOf<JsonObject>()
        val included = mutableMapOf<String, JsonObject>()
        var next: String? = path
        var pages = 0
        while (next != null && pages < maxPages) {
            val page = call("GET", next, null) ?: break
            (page["data"] as? JsonArray)?.forEach { out.add(it.jsonObject) }
            (page["included"] as? JsonArray)?.forEach { el ->
                val o = el.jsonObject
                o.str("id")?.let { included[it] = o }
            }
            next = (page["links"] as? JsonObject)?.get("next")?.let { it as? JsonObject }?.str("href")
            pages++
        }
        return ApiList(out, included)
    }

    /** Lädt nur die erste Seite. */
    suspend fun getFirstPage(path: String): ApiList = getAll(path, maxPages = 1)

    /** Lädt eine Datei hoch (JSON:API-Datei-Upload) und liefert die Antwort. */
    suspend fun upload(path: String, file: File): JsonObject? = call("UPLOAD", path, null, file)

    /**
     * HTTP-Client für Bilder: hängt das Zugriffstoken an Anfragen an den eigenen Server
     * (farmOS speichert Fotos meist als private Dateien).
     */
    val imageClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val base = prefs.serverUrl.toHttpUrlOrNull()
                val token = if (base != null && req.url.host == base.host && prefs.refreshToken != null) {
                    runCatching { runBlocking { validToken() } }.getOrNull()
                } else null
                if (token != null) {
                    chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
                } else {
                    chain.proceed(req)
                }
            })
            .build()
    }

    suspend fun send(method: String, path: String, body: String?): JsonObject? = call(method, path, body)

    companion object {
        fun normalizeUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }

        fun isNetworkError(e: Throwable): Boolean = e is IOException
    }
}
