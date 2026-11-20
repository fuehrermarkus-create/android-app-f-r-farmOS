package at.hof.farmos.data

import android.content.Context

/** Einfache Einstellungen: Server, Zugangsdaten (nur Tokens, kein Passwort). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("farmos_hof", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var clientId: String
        get() = sp.getString("client_id", "farm") ?: "farm"
        set(v) = sp.edit().putString("client_id", v.trim()).apply()

    var scope: String
        get() = sp.getString("scope", "farm_manager") ?: "farm_manager"
        set(v) = sp.edit().putString("scope", v.trim()).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    /** Zuletzt verwendeter Anwender (Pflanzenschutz-Aufzeichnung). */
    var applicator: String
        get() = sp.getString("applicator", "") ?: ""
        set(v) = sp.edit().putString("applicator", v.trim()).apply()

    /** Gemerkte Angaben je Mittel/Dünger (Zulassungsnummer, N-Gehalt …) als JSON-Objekt je Name. */
    fun materialInfo(name: String): Map<String, String> {
        val all = runCatching { org.json.JSONObject(sp.getString("material_info", "{}") ?: "{}") }.getOrNull()
            ?: return emptyMap()
        val o = all.optJSONObject(name.trim().lowercase()) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun rememberMaterial(name: String, info: Map<String, String?>) {
        val all = runCatching { org.json.JSONObject(sp.getString("material_info", "{}") ?: "{}") }.getOrNull()
            ?: org.json.JSONObject()
        val o = all.optJSONObject(name.trim().lowercase()) ?: org.json.JSONObject()
        info.forEach { (k, v) -> if (!v.isNullOrBlank()) o.put(k, v.trim()) }
        all.put(name.trim().lowercase(), o)
        sp.edit().putString("material_info", all.toString()).apply()
    }

    var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit().putString("refresh_token", v).apply()

    /** Ablaufzeitpunkt des Access-Tokens in Millisekunden. */
    var tokenExpiry: Long
        get() = sp.getLong("token_expiry", 0L)
        set(v) = sp.edit().putLong("token_expiry", v).apply()

    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    /** farmOS 4: Assets haben „archived“ statt „status“ (wird automatisch erkannt). */
    var assetsUseArchived: Boolean
        get() = sp.getBoolean("assets_use_archived", false)
        set(v) = sp.edit().putBoolean("assets_use_archived", v).apply()

    // Nextcloud-Fotoablage
    var ncUrl: String
        get() = sp.getString("nc_url", "") ?: ""
        set(v) = sp.edit().putString("nc_url", v.trim().trimEnd('/')).apply()
    var ncUser: String
        get() = sp.getString("nc_user", "") ?: ""
        set(v) = sp.edit().putString("nc_user", v.trim()).apply()
    var ncPassword: String
        get() = sp.getString("nc_password", "") ?: ""
        set(v) = sp.edit().putString("nc_password", v.trim()).apply()
    var ncFolder: String
        get() = sp.getString("nc_folder", "farmOS Fotos") ?: "farmOS Fotos"
        set(v) = sp.edit().putString("nc_folder", v.trim().trim('/').ifBlank { "farmOS Fotos" }).apply()

    /** Letzter Fehler beim Abgleich (bleibt sichtbar, bis ein Abgleich klappt). */
    var lastSyncError: String?
        get() = sp.getString("last_sync_error", null)
        set(v) = sp.edit().putString("last_sync_error", v).apply()

    var lastSyncErrorAt: Long
        get() = sp.getLong("last_sync_error_at", 0L)
        set(v) = sp.edit().putLong("last_sync_error_at", v).apply()

    val isLoggedIn: Boolean
        get() = serverUrl.isNotBlank() && (refreshToken != null || accessToken != null)

    fun logout() {
        sp.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("token_expiry")
            .remove("last_sync")
            .remove("last_sync_error")
            .remove("last_sync_error_at")
            .apply()
    }
}
