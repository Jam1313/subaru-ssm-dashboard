package kg.forester.sg.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub releases of the project (betas included): finds the newest release, downloads its APK
 * and hands it to the system installer. Releases are signed with the same key, so the update keeps the data.
 */
object Updater {
    private const val REPO = "surtaeff/subaru-ssm-dashboard"

    data class Release(val tag: String, val name: String, val notes: String, val apkUrl: String, val size: Long)

    fun current(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    /** The newest published release that has an APK, or null (no network, GitHub limit). Blocking. */
    fun latest(): Release? = runCatching {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=15").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000; c.readTimeout = 10_000
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }
        val withApk = all.mapNotNull { r ->
            val assets = r.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk") }
                .sortedBy { if (it.getString("name") == "subaru-ssm-dashboard.apk") 0 else 1 }
                .firstOrNull() ?: return@mapNotNull null
            Release(r.getString("tag_name"), r.optString("name"), r.optString("body"),
                apk.getString("browser_download_url"), apk.optLong("size"))
        }
        withApk.maxWithOrNull { a, b -> compare(a.tag, b.tag) }
    }.getOrNull()

    /** True when [tag] (e.g. "v0.9-beta7") is newer than [installed] (e.g. "0.9-beta6"). */
    fun isNewer(tag: String, installed: String) = compare(tag, installed) > 0

    /** Versions like 0.8, 0.9-beta3, 1.0: numbers first, a beta is older than the same number without it. */
    private fun compare(a: String, b: String): Int {
        fun parse(s: String): Pair<List<Int>, Int> {
            val t = s.trim().removePrefix("v").lowercase()
            val nums = t.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val beta = Regex("beta(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            return nums to beta
        }
        val (na, ba) = parse(a)
        val (nb, bb) = parse(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val d = na.getOrElse(i) { 0 } - nb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return ba.compareTo(bb)
    }

    /** Downloads the APK into the cache; [progress] gets 0..1. Blocking. */
    fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "subaru-dashboard-${r.tag}.apk")
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        // GitHub answers with a redirect to its file storage
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        c.inputStream.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        return f
    }

    /** False when Android first wants "install unknown apps" allowed for this app (the settings page is opened). */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
