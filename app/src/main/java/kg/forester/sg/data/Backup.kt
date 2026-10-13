package kg.forester.sg.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of everything the user built up: the database (trips, refills, charts, battery), all settings
 * (calibration, layouts, own gauge designs…) and the user's pictures. A zip with the app's own folder layout:
 * databases/, shared_prefs/, files/. Daily copies go to Download/SubaruDashboard, which outlives the app,
 * so data survives a reinstall (e.g. a release signed with another key).
 */
object Backup {
    private const val DB = "forester.db"
    private const val FOLDER = "SubaruDashboard"
    private const val KEEP = 7
    /** Prefs files worth keeping; "gauge_images" only holds a pending gallery request. */
    private val PREFS = listOf("settings", "shortcuts", "custom_gauges", "plate", "voice")
    /** Files under files/ (pictures); logs and caches are left out. */
    private val FILES = listOf("gauges", "plate.png")

    private fun dataDir(ctx: Context) = File(ctx.applicationInfo.dataDir)

    fun write(ctx: Context, out: OutputStream) {
        // fold the write-ahead log into the database file before copying it
        runCatching { Repo.db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(f: File, name: String) {
                if (!f.isFile) return
                zip.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            put(ctx.getDatabasePath(DB), "databases/$DB")
            PREFS.forEach { put(File(dataDir(ctx), "shared_prefs/$it.xml"), "shared_prefs/$it.xml") }
            FILES.forEach { name ->
                val f = File(ctx.filesDir, name)
                if (f.isDirectory) f.listFiles()?.forEach { put(it, "files/$name/${it.name}") } else put(f, "files/$name")
            }
        }
    }

    /**
     * Replaces the current data with the backup in [input] and restarts the app (the open database and the
     * settings cached in memory would otherwise keep the old data). Returns an error text if the file is not
     * a backup of this app; then nothing is changed.
     */
    fun restore(ctx: Context, input: InputStream): String? {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = File(tmp, e.name).canonicalFile
                if (!target.path.startsWith(tmp.canonicalPath + File.separator)) continue // no "../" escapes
                if (e.isDirectory) continue
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
        if (!File(tmp, "databases/$DB").isFile) {
            tmp.deleteRecursively()
            return "Это не резервная копия Subaru Dashboard"
        }
        // stop everything that writes, then swap the files and start over
        ctx.stopService(Intent(ctx, kg.forester.sg.service.EcuService::class.java))
        runCatching { Repo.db.close() }
        val db = ctx.getDatabasePath(DB)
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal")).forEach { it.delete() }
        File(tmp, "databases/$DB").copyTo(db, overwrite = true)
        File(tmp, "shared_prefs").listFiles()?.forEach { it.copyTo(File(dataDir(ctx), "shared_prefs/${it.name}"), overwrite = true) }
        File(tmp, "files").listFiles()?.forEach { it.copyRecursively(File(ctx.filesDir, it.name), overwrite = true) }
        tmp.deleteRecursively()
        val restart = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(restart)
        Runtime.getRuntime().exit(0)
        return null
    }

    fun suggestedName() = "subaru-dashboard-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".zip"

    /** Daily copy into Download/SubaruDashboard (kept: the last [KEEP]); called from the hourly housekeeping. */
    fun autoBackupIfDue(ctx: Context) {
        val s = Repo.settings
        if (!s.autoBackup) return
        val now = System.currentTimeMillis()
        if (now - s.lastAutoBackupMs < 23 * 3600_000L) return
        if (Repo.db.refills().isEmpty() && Repo.db.trips(1).isEmpty()) return // nothing worth keeping yet
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 29) writeToDownloads(ctx) else writeToLegacyDownloads(ctx)
        }.getOrDefault(false)
        if (ok) s.lastAutoBackupMs = now
    }

    private fun writeToDownloads(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = cr.insert(coll, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, suggestedName())
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        }) ?: return false
        cr.openOutputStream(uri)?.use { write(ctx, it) } ?: return false
        // older copies made by this install; the user's other files in the folder are not visible to the app anyway
        cr.query(
            coll, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE 'subaru-dashboard-%'",
            arrayOf("%$FOLDER%"), "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext()) if (++n > KEEP) cr.delete(Uri.withAppendedPath(coll, c.getLong(0).toString()), null, null)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyDownloads(ctx: Context): Boolean {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        File(dir, suggestedName()).outputStream().use { write(ctx, it) }
        dir.listFiles { f -> f.name.startsWith("subaru-dashboard-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        return true
    }

    fun lastAutoText(): String {
        val t = Repo.settings.lastAutoBackupMs
        return if (t == 0L) "ещё не было" else SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(t))
    }
}
