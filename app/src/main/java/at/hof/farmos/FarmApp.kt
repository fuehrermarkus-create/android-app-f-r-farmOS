package at.hof.farmos

import android.app.Application
import at.hof.farmos.data.FarmApi
import at.hof.farmos.data.Prefs
import at.hof.farmos.data.Repository
import at.hof.farmos.data.db.FarmDatabase
import at.hof.farmos.photo.PhotoStore
import at.hof.farmos.sync.SyncWorker
import coil.ImageLoader
import coil.ImageLoaderFactory

class FarmApp : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs
        private set
    lateinit var api: FarmApi
        private set
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = FarmApi(prefs)
        val db = FarmDatabase.create(this)
        repository = Repository(api, db.dao(), prefs, PhotoStore.dir(this))
        SyncWorker.schedulePeriodic(this)
    }

    /** Bilder vom eigenen Server werden mit Zugriffstoken geladen. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { api.imageClient }
            .crossfade(true)
            .build()
}
