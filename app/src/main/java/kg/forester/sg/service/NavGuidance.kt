package kg.forester.sg.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/** The next manoeuvre as a navigator shows it in its notification: "300 м", "Поверните направо на ул. Ленина", an arrow. */
data class NavHint(
    val pkg: String,
    val title: String,
    val text: String,
    val sub: String,
    val icon: Bitmap?,
)

/**
 * Turn hints for the «Приборка с картой» card, read from the navigators' own notifications while they guide
 * (Google Maps, 2GIS and others print the distance, the street and an arrow there). Another app's map cannot be
 * shown inside this one, its notification can be read: that is the [MediaListener]'s permission, the one the
 * media controls already ask for. Only notifications of navigation apps are looked at, nothing is logged.
 */
object NavGuidance {
    val hint = MutableStateFlow<NavHint?>(null)
    private var key: String? = null

    private val NavApps = setOf(
        "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.google.android.apps.maps", "ru.dublgis.dgismobile",
        "com.waze", "net.osmand.plus", "net.osmand", "com.sygic.aura", "cityguide.probki.net", "app.organicmaps",
        "com.here.app.maps", "com.mapswithme.maps.pro",
    )

    fun posted(ctx: Context, sbn: StatusBarNotification) = runCatching {
        val n = sbn.notification ?: return@runCatching
        if (sbn.packageName !in NavApps && n.category != Notification.CATEGORY_NAVIGATION) return@runCatching
        // guidance runs as an ongoing notification; reviews, offers and traffic news are not
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return@runCatching
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        // a manoeuvre always comes with a distance or a time; "Навигатор работает в фоне" does not
        if (!(title + text).any { it.isDigit() }) {
            if (sbn.key == key) clear()
            return@runCatching
        }
        key = sbn.key
        hint.value = NavHint(sbn.packageName, title, text, sub, n.getLargeIcon()?.loadDrawable(ctx)?.let(::bitmapOf))
    }

    fun removed(sbn: StatusBarNotification) {
        if (sbn.key == key) clear()
    }

    private fun clear() {
        key = null
        hint.value = null
    }

    private fun bitmapOf(d: android.graphics.drawable.Drawable): Bitmap {
        val side = 128
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, side, side)
        d.draw(android.graphics.Canvas(b))
        return b
    }
}
