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
