package at.hof.farmos.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * Speichert Fotos verkleinert in der App und schreibt Zeit, GPS und Beschreibung in die EXIF-Daten,
 * damit die Metadaten auch in der Bilddatei selbst stecken.
 */
object PhotoStore {

    private const val MAX_SIZE = 1600
    private const val QUALITY = 85

    data class Imported(
        val path: String,
        val takenAt: Long?,     // Sekunden (Unix), aus EXIF
        val latitude: Double?,
        val longitude: Double?,
    )

    fun dir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

    /** Ziel für die Kamera-App (wird über FileProvider freigegeben). */
    fun newCaptureTarget(context: Context): Pair<Uri, File> {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "cap_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return uri to file
    }

    /** Liest ein Bild (Kamera oder Galerie), dreht und verkleinert es und legt es in der App ab. */
    fun import(context: Context, uri: Uri): Imported? {
        val resolver = context.contentResolver

        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val latLong = runCatching { exif?.latLong }.getOrNull()
        val takenAt = exif?.let { readTime(it) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp: Bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val longest = max(bmp.width, bmp.height)
        if (longest > MAX_SIZE) {
            val f = MAX_SIZE.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir(context), "IMG_${stamp}_${UUID.randomUUID().toString().take(6)}.jpg")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        bmp.recycle()

        // Metadaten des Originals mitnehmen
        writeMeta(out.absolutePath, takenAt, latLong?.getOrNull(0), latLong?.getOrNull(1), null)

        return Imported(out.absolutePath, takenAt, latLong?.getOrNull(0), latLong?.getOrNull(1))
    }

    /** Schreibt Aufnahmezeit, Position und Beschreibung in die EXIF-Daten. */
    fun writeMeta(path: String, epochSeconds: Long?, lat: Double?, lon: Double?, description: String?) {
        runCatching {
            val e = ExifInterface(path)
            if (epochSeconds != null) {
                val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(epochSeconds * 1000))
                e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
                e.setAttribute(ExifInterface.TAG_DATETIME, dt)
            }
            if (lat != null && lon != null) e.setLatLong(lat, lon)
            if (!description.isNullOrBlank()) {
                val ascii = asciiOnly(description)
                e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, ascii)
                e.setAttribute(ExifInterface.TAG_USER_COMMENT, ascii)
            }
            e.setAttribute(ExifInterface.TAG_SOFTWARE, "farmOS Hof")
            e.saveAttributes()
        }
    }

    private fun readTime(e: ExifInterface): Long? {
        val s = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: e.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time?.div(1000)
        }.getOrNull()
    }

    /** EXIF-Textfelder sind ASCII – Umlaute umschreiben. */
    private fun asciiOnly(s: String): String = s
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
        .replace(Regex("[^\\x20-\\x7E]"), "")
}
