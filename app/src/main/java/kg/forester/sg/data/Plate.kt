package kg.forester.sg.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Licence plate shown on the 3D car. Either generated from text (default "SG5") or a user image.
 * The rendered car frames have blank plates; CarViewer warps this bitmap onto them.
 */
object Plate {
    val bitmap = MutableStateFlow<Bitmap?>(null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("plate", Context.MODE_PRIVATE)
    private fun imageFile(ctx: Context) = File(ctx.filesDir, "plate.png")

    fun text(ctx: Context) = prefs(ctx).getString("text", "SG5")!!
    fun flag(ctx: Context) = prefs(ctx).getBoolean("flag", false)
    fun usesImage(ctx: Context) = prefs(ctx).getBoolean("image", false) && imageFile(ctx).exists()

    fun load(ctx: Context) {
        bitmap.value = if (usesImage(ctx)) BitmapFactory.decodeFile(imageFile(ctx).absolutePath) ?: render(text(ctx), flag(ctx))
        else render(text(ctx), flag(ctx))
    }

    fun setText(ctx: Context, text: String, flag: Boolean) {
        prefs(ctx).edit().putString("text", text.trim().ifBlank { "SG5" }).putBoolean("flag", flag).putBoolean("image", false).apply()
        load(ctx)
    }

    fun setImage(ctx: Context, uri: Uri): Boolean {
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { inp -> imageFile(ctx).outputStream().use { inp.copyTo(it) } }
            BitmapFactory.decodeFile(imageFile(ctx).absolutePath) != null
        }.getOrDefault(false)
        if (ok) {
            prefs(ctx).edit().putBoolean("image", true).apply()
            load(ctx)
        }
        return ok
    }

    /** White plate with a black frame, condensed bold text and an optional red KG flag block. */
    fun render(text: String, flag: Boolean): Bitmap {
        val w = 1040
        val h = 226
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 8f
        p.color = Color.BLACK
        c.drawRoundRect(RectF(5f, 5f, w - 5f, h - 5f), 18f, 18f, p)
        p.style = Paint.Style.FILL
        var left = 0f
        if (flag) {
            p.color = Color.rgb(232, 17, 45)
            c.drawRoundRect(RectF(18f, 18f, 150f, h - 18f), 8f, 8f, p)
            p.color = Color.rgb(255, 215, 0)
            c.drawCircle(84f, h / 2f - 18f, 32f, p)
            p.color = Color.WHITE
            p.textSize = 34f
            p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            c.drawText("KG", 84f, h - 38f, p)
            left = 150f
        }
        p.color = Color.BLACK
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = 170f
        val avail = w - left - 40f
        val tw = p.measureText(text)
        if (tw > avail) p.textSize *= avail / tw
        val fm = p.fontMetrics
        c.drawText(text, left + (w - left) / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
        return bmp
    }
}
