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
    /** Recent warnings, newest first, for the «Мониторинг» screen. */
    val history = MutableStateFlow<List<Alert>>(emptyList())

    private val lastShown = HashMap<String, Long>()
    private val lastLevel = HashMap<String, Level>()
    private var prefs: android.content.SharedPreferences? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun init(ctx: Context) {
        if (Repo.settings.voiceAlerts) Speech.prepare(ctx)
        if (prefs == null) {
            // the list survives a restart, so a warning heard while driving can be read later
            val p = ctx.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE).also { prefs = it }
            val saved = runCatching {
                val a = org.json.JSONArray(p.getString("history", "[]"))
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Alert(o.getString("id"), o.getString("text"), Level.valueOf(o.getString("level")), o.getLong("time"))
                }
            }.getOrDefault(emptyList())
            history.value = (history.value + saved).take(50)
            save()
        }
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            ttsReady = status == TextToSpeech.SUCCESS &&
                t.setLanguage(Locale("ru", "RU")).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
            // like a navigator's voice: over the music, not at the notification volume
            runCatching {
                t.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }
    }

    /**
     * Shows (and says) [text] unless the same [id] was raised less than [cooldownMs] ago; a more serious [level]
     * than last time gets through all the same (a warning must not hold back the danger that follows it).
     */
    fun raise(id: String, text: String, level: Level = Level.WARN, cooldownMs: Long = 10 * 60_000L, speak: Boolean = true, banner: Boolean = true) {
        val now = System.currentTimeMillis()
        synchronized(lastShown) {
            val last = lastShown[id]
            val worse = lastLevel[id]?.let { level > it } ?: false
            if (last != null && now - last < cooldownMs && !worse) return
            lastShown[id] = now
            lastLevel[id] = level
        }
        val a = Alert(id, text, level, now)
        if (banner) current.value = a
        history.value = (listOf(a) + history.value).take(50)
        save()
        if (!speak || !Repo.settings.voiceAlerts || Repo.settings.demo) return
        // the app's own voice first, then the head unit's TTS, then a beep
        if (Speech.say(text)) return
        if (ttsReady) {
            val loud = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            runCatching { tts?.speak(SpeechText.prepare(text), TextToSpeech.QUEUE_ADD, loud, id) }
        } else if (level != Level.INFO) {
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
        }
    }

    private fun save() {
        val p = prefs ?: return
        val a = org.json.JSONArray()
        history.value.forEach {
            a.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("level", it.level.name).put("time", it.timeMs))
        }
        p.edit().putString("history", a.toString()).apply()
    }

    /** The banner goes away when the condition is over (e.g. the turbo has cooled down). */
    fun clear(id: String) {
        if (current.value?.id == id) current.value = null
    }

    fun dismiss() {
        current.value = null
    }
}
