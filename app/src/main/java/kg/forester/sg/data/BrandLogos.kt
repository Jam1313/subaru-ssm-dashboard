package kg.forester.sg.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Logos for the header and the start-up screen: the make's logo from the app's assets, else downloaded once into
 * files/brands (small PNGs, offline = the make's name instead), or the driver's own picture.
 */
object BrandLogos {
    /** Bumped when a logo file appears or changes: the header picks it up. */
    val version = MutableStateFlow(0)

    private const val MAX_SIDE = 1024
    private const val MAX_BYTES = 512 * 1024
    private val cache = LruCache<String, Bitmap>(6)
    /** When a download of a make was last tried: offline it is not tried again for a while. */
    private val tried = HashMap<String, Long>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "brands").apply { mkdirs() }

    /** A downloaded logo of [slug], null if there is none yet. */
    fun cached(ctx: Context, slug: String): String? =
        File(dir(ctx), "$slug.png").takeIf { it.length() > 0 }?.absolutePath

    /** The driver's own picture: "logo" (the header) or "splash" (the start-up screen); null if not chosen or gone. */
    fun custom(ctx: Context, which: String): String? {
        val name = if (which == "splash") Repo.settings.splashImage else Repo.settings.logoImage
        if (name.isEmpty()) return null
        return File(dir(ctx), name).takeIf { it.isFile }?.absolutePath
    }

    /** The logo of [slug] from [url] into files/brands, in the background, at most every 10 minutes per make. */
    fun download(ctx: Context, slug: String, url: String) {
        val app = ctx.applicationContext
        synchronized(tried) {
            val now = System.currentTimeMillis()
            if (now - (tried[slug] ?: 0L) < 10 * 60_000L || cached(app, slug) != null) return
            tried[slug] = now
        }
        Thread {
            runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 8000
                val bytes = try {
                    if (c.responseCode != 200) return@runCatching
                    c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_BYTES) return@runCatching // not a small logo
                        }
                        out.toByteArray()
                    }
                } finally { c.disconnect() }
                // only a picture that decodes is kept
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                val tmp = File(dir(app), "$slug.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(dir(app), "$slug.png"))
                version.value++
            }
        }.start()
    }

    /** A picture from the gallery as the own logo or start-up picture ([which] "logo" / "splash"), kept in files/brands. */
    fun importCustom(ctx: Context, which: String, uri: Uri) {
        val name = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val src = ctx.contentResolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return
            val k = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
            val out = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
            val n = "${which}_${System.currentTimeMillis()}.png"
            // PNG keeps a transparent background (a logo cut out on its own)
            File(dir(ctx), n).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            n
        }.getOrNull() ?: return
        val s = Repo.settings
        val old = if (which == "splash") s.splashImage else s.logoImage
        if (old.isNotEmpty()) File(dir(ctx), old).delete()
        if (which == "splash") { s.splashImage = name; s.splashMode = "custom" } else { s.logoImage = name; s.logoMode = "custom" }
        version.value++
    }

    /** A logo picture: a file path, or "asset:<path>" for one in the app. */
    fun bitmap(ctx: Context, ref: String): Bitmap? = cache.get(ref) ?: runCatching {
        if (ref.startsWith("asset:")) ctx.assets.open(ref.removePrefix("asset:")).use { BitmapFactory.decodeStream(it) }
        else BitmapFactory.decodeFile(ref)
    }.getOrNull()?.also { cache.put(ref, it) }

    private val darkness = HashMap<String, Boolean>()

    /**
     * A logo drawn in black or dark grey (Toyota, VW, Audi, Nissan…) is lost on the dark header: by the mean brightness
     * of its opaque pixels (the bundled ones: Audi, Nissan,
     * Toyota, VW, Lincoln, Jaguar, Zeekr…), such a logo is drawn light instead.
     */
    fun isDark(ref: String, b: Bitmap): Boolean = synchronized(darkness) {
        darkness.getOrPut(ref) {
            val step = maxOf(1, kotlin.math.sqrt(b.width * b.height / 4096.0).toInt())
            var sum = 0.0
            var n = 0
            for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
                val c = b.getPixel(x, y)
                if ((c ushr 24) < 128) continue
                // the brightest channel, not the luminance: a red logo (Mitsubishi, Tesla) shows well as it is
                sum += maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) / 255.0
                n++
            }
            n > 0 && sum / n < 0.36
        }
    }

    /** The driver's own picture (kept as it is), not a make's logo. */
    fun isOwn(ref: String) = Regex("(logo|splash)_\\d+\\.png").matches(ref.substringAfterLast('/'))

    /** The header's logo now: by the setting, the car kind and the make (see [CarKind.chooseLogo]). */
    fun pick(ctx: Context, mode: String, other: Boolean, make: Brand?): CarKind.LogoPick {
        val slug = make?.slug
        return CarKind.chooseLogo(
            mode, other, custom(ctx, "logo"), slug, make?.name,
            slug?.let { runCatching { Brands.get(ctx).logoAsset(it) }.getOrNull() },
            slug?.let { cached(ctx, it) },
            slug?.let { runCatching { Brands.get(ctx).remoteLogoUrl(it) }.getOrNull() },
        )
    }

    /** The start-up screen by the setting; the car is the last one that gave its VIN. */
    fun splash(ctx: Context): CarKind.SplashPick {
        val s = Repo.settings
        val make = s.lastVin.takeIf { it.isNotEmpty() }?.let { runCatching { Brands.get(ctx).makeFromVin(it) }.getOrNull() }
        val other = when {
            s.carKind == "subaru" -> false
            s.carKind == "other" -> true
            make != null -> make.slug != Brands.SUBARU
            else -> s.carKindLast == "other"
        }
        // «как сейчас» is the Subaru picture for a Subaru; another car gets its own logo there instead
        val mode = if (s.splashMode == "subaru" && other) "car" else s.splashMode
        return CarKind.chooseSplash(mode, custom(ctx, "splash"), pick(ctx, "auto", other, make))
    }
}
