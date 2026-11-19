package at.hof.farmos.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import at.hof.farmos.FarmApp
import java.util.concurrent.TimeUnit

/** Lädt im Hintergrund offline erfasste Änderungen hoch, sobald Netz da ist. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FarmApp
        if (!app.prefs.isLoggedIn) return Result.success()
        // Regelmäßig: hoch- UND herunterladen; direkt nach dem Speichern: nur hochladen
        val full = inputData.getBoolean("full", false)
        val result = if (full) app.repository.sync() else app.repository.pushPending()
        return when {
            result.offline -> Result.retry()
            result.error != null && result.failed == 0 -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun syncSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("push-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(network)
                .setInputData(androidx.work.workDataOf("full" to true))
                .build()
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("push-periodic") // alter, nur hochladender Auftrag
            wm.enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
