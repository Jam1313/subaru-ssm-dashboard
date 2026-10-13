package kg.forester.sg.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import kg.forester.sg.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Warnings for the driver (IAM drop, overheating, cold engine revved, maintenance due, low tyre…): a banner on
 * every screen and, if the head unit has a Russian voice, spoken; otherwise a beep. Each warning has an id and
 * is repeated no more often than its cooldown.
 */
object Alerts {
    enum class Level { INFO, WARN, DANGER }

    data class Alert(val id: String, val text: String, val level: Level, val timeMs: Long)

    /** The banner shown now (the latest one), null = none. */
    val current = MutableStateFlow<Alert?>(null)
    /** Recent warnings, newest first, for the «Здоровье» screen. */
    val history = MutableStateFlow<List<Alert>>(emptyList())

    private val lastShown = HashMap<String, Long>()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun init(ctx: Context) {
        if (Repo.settings.voiceAlerts) Speech.prepare(ctx)
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            ttsReady = status == TextToSpeech.SUCCESS &&
                t.setLanguage(Locale("ru", "RU")).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
        }
    }

    /** Shows (and says) [text] unless the same [id] was raised less than [cooldownMs] ago. */
    fun raise(id: String, text: String, level: Level = Level.WARN, cooldownMs: Long = 10 * 60_000L, speak: Boolean = true, banner: Boolean = true) {
        val now = System.currentTimeMillis()
        synchronized(lastShown) {
            val last = lastShown[id]
            if (last != null && now - last < cooldownMs) return
            lastShown[id] = now
        }
        val a = Alert(id, text, level, now)
        if (banner) current.value = a
        history.value = (listOf(a) + history.value).take(50)
        if (!speak || !Repo.settings.voiceAlerts || Repo.settings.demo) return
        // the app's own voice first, then the head unit's TTS, then a beep
        if (Speech.say(text)) return
        if (ttsReady) {
            runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) }
        } else if (level != Level.INFO) {
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
        }
    }

    /** The banner goes away when the condition is over (e.g. the turbo has cooled down). */
    fun clear(id: String) {
        if (current.value?.id == id) current.value = null
    }

    fun dismiss() {
        current.value = null
    }
}
