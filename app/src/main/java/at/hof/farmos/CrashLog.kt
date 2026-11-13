package at.hof.farmos

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speichert Abstürze in einer Datei, damit sie beim nächsten Start angezeigt
 * und geteilt werden können (ohne PC/USB-Debugging).
 */
object CrashLog {
    private const val FILE = "letzter_absturz.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull() ?: "?"
                val text = buildString {
                    append("Zeit: ").append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())).append('\n')
                    append("App: farmOS Hof ").append(version).append('\n')
                    append("Gerät: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(Log.getStackTraceString(error))
                }
                File(app.filesDir, FILE).writeText(text.take(60_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
