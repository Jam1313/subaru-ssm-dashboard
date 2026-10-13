package kg.forester.sg.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import kg.forester.sg.data.Shortcut
import kg.forester.sg.data.Shortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Screens the voice can open in our UI. */
enum class VoiceTarget { HOME, DRIVE, FUEL, GEARBOX, BATTERY, DTC, MEDIA }

/**
 * Offline voice control (Vosk, small Russian model downloaded on first use).
 * Always listening; reacts to "<wake word> <command>" or the wake word followed by a command within a few seconds.
 */
object Voice {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val MODEL_DIR = "vosk-ru"

    val status = MutableStateFlow("выключено")
    val lastHeard = MutableStateFlow("")
    val toast = MutableStateFlow<Pair<Long, String>?>(null)
    val navigate = MutableStateFlow<Pair<Long, VoiceTarget>?>(null)

    private var service: Thread? = null
    @Volatile private var running = false
    private var model: Model? = null
    private var armedUntil = 0L
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var app: Context
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun wakeWord(ctx: Context) = prefs(ctx).getString("wake", "форестер")!!
    fun setWakeWord(ctx: Context, w: String) = prefs(ctx).edit().putString("wake", w.trim().lowercase()).apply()

    /** Software microphone gain, 1..4. */
    fun gain(ctx: Context) = prefs(ctx).getFloat("gain", 2f)
    fun setGain(ctx: Context, g: Float) = prefs(ctx).edit().putFloat("gain", g).apply()

    /** true: plain MIC source (more sensitive on many head units), false: VOICE_RECOGNITION. */
    fun sensitive(ctx: Context) = prefs(ctx).getBoolean("micSource", true)
    fun setSensitive(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("micSource", v).apply()

    fun restart(ctx: Context) { stop(); start(ctx) }

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (on) start(ctx) else stop()
    }

    fun modelReady(ctx: Context) = File(ctx.filesDir, "$MODEL_DIR/am").exists() || File(ctx.filesDir, "$MODEL_DIR/conf").exists()

    fun start(ctx: Context) {
        app = ctx.applicationContext
        android.util.Log.i("Voice", "start: enabled=${enabled(app)} running=${service != null} model=${modelReady(app)}")
        if (!enabled(app) || service != null || !starting.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                if (!modelReady(app)) download(app)
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = model ?: Model(File(app.filesDir, MODEL_DIR).absolutePath).also { model = it }
                val rec = Recognizer(m, 16000f)
                running = true
                service = Thread({ listenLoop(rec) }, "voice").also { it.start() }
                status.value = "слушаю «${wakeWord(app)}»"
                starting.set(false)
            }.onFailure {
                starting.set(false)
                android.util.Log.e("Voice", "start failed", it)
                status.value = "ошибка: ${it.message}"
                service = null
            }
        }
    }

    fun stop() {
        running = false
        runCatching { service?.join(1500) }
        service = null
        status.value = "выключено"
    }

    private fun download(ctx: Context) {
        status.value = "скачиваю модель распознавания (45 МБ)…"
        android.util.Log.i("Voice", "downloading model")
        val tmp = File(ctx.cacheDir, "vosk.zip")
        URL(MODEL_URL).openStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        status.value = "распаковываю модель…"
        val out = File(ctx.filesDir, MODEL_DIR).apply { deleteRecursively(); mkdirs() }
        ZipInputStream(tmp.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                // strip the top-level "vosk-model-small-ru-0.22/" folder
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotEmpty()) {
                    val f = File(out, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
                e = zip.nextEntry
            }
        }
        tmp.delete()
    }

    /** Own capture loop so we can boost quiet head-unit microphones and use the platform AGC / noise suppressor. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun listenLoop(rec: Recognizer) {
        val rate = 16000
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val source = if (sensitive(app)) android.media.MediaRecorder.AudioSource.MIC else android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        val ar = android.media.AudioRecord(source, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        val agc = runCatching { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(ar.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val ns = runCatching { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = true } else null }.getOrNull()
        val buf = ShortArray(2048)
        try {
            ar.startRecording()
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val g = gain(app)
                if (g != 1f) for (i in 0 until n) {
                    buf[i] = (buf[i] * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                if (rec.acceptWaveForm(buf, n)) handle(rec.result)
            }
        } catch (e: Exception) {
            status.value = "ошибка микрофона: ${e.message}"
        } finally {
            runCatching { ar.stop() }; ar.release(); agc?.release(); ns?.release(); rec.close()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun handle(json: String?) = main.post { handleOnMain(json) }

    private fun handleOnMain(json: String?) {
        val text = runCatching { JSONObject(json ?: "").optString("text") }.getOrDefault("").trim().lowercase()
        if (text.isEmpty()) return
        lastHeard.value = text
        android.util.Log.i("Voice", "heard: $text")
        val words = text.split(" ").filter { it.isNotBlank() }
        val wake = wakeWord(app)
        val wakeIdx = words.indexOfFirst { similar(it, wake) }
        val now = SystemClock.elapsedRealtime()
        val command = when {
            wakeIdx >= 0 -> words.drop(wakeIdx + 1).joinToString(" ")
            now < armedUntil -> text
            else -> return
        }
        if (command.isBlank()) {
            armedUntil = now + 6000
            beep()
            say("Слушаю…")
            return
        }
        armedUntil = 0
        execute(command)
    }

    private fun execute(cmd: String) {
        val c = cmd.lowercase()
        // custom buttons: label or voice phrase
        val sc: Shortcut? = Shortcuts.list.value.firstOrNull { s ->
            listOf(s.label, s.phrase).filter { it.isNotBlank() }.any { phraseMatches(c, it.lowercase()) }
        }
        when {
            listOf("поехали", "маршрут", "едем", "проложи", "найди").any { c.startsWith(it) } -> {
                val q = c.substringAfter(' ', "").removePrefix("на ").removePrefix("в ").removePrefix("до ").trim()
                if (q.isNotEmpty()) navigateTo(q) else say("Не расслышал адрес")
            }
            sc != null -> { say(sc.label); Shortcuts.launch(app, sc) }
            "весь экран" in c || "на весь" in c -> { say("Весь экран"); Shortcuts.exitSplit(app) }
            "громче" in c -> volume(+2)
            "тише" in c -> volume(-2)
            // the head unit's own player / radio / BT get FYT commands; anything else playing (Yandex Music,
            // Spotify, VK…) gets the standard media keys, which go to the app that plays now
            "следующ" in c || "дальше" in c || "далее" in c -> { if (fytPlaying()) Fyt.next() else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, ""); say("Следующий") }
            "предыдущ" in c || "назад" in c -> { if (fytPlaying()) Fyt.prev() else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ""); say("Предыдущий") }
            "пауза" in c || "стоп" in c -> { if (Fyt.music.value.playing) Fyt.playPause() else mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, ""); say("Пауза") }
            "играй" in c || "продолж" in c || "включи музыку" in c -> {
                if (otherAppPlaying() || !Fyt.present(app)) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "") else if (!Fyt.music.value.playing) Fyt.playPause()
                say("Играю")
            }
            "радио" in c -> { Fyt.ensureRadio(); go(VoiceTarget.MEDIA, "Радио") }
            "расход" in c -> go(VoiceTarget.FUEL, "Расход")
            "коробк" in c || "акпп" in c -> go(VoiceTarget.GEARBOX, "АКПП")
            "аккумулятор" in c || "батаре" in c -> go(VoiceTarget.BATTERY, "Аккумулятор")
            "ошибк" in c -> go(VoiceTarget.DTC, "Ошибки")
            "динамик" in c || "наддув" in c -> go(VoiceTarget.DRIVE, "Динамика")
            "главн" in c || "приборы" in c -> go(VoiceTarget.HOME, "Главная")
            "медиа" in c || "плеер" in c -> go(VoiceTarget.MEDIA, "Медиа")
            else -> say("Не понял: $cmd")
        }
    }

    /** Opens Yandex Navigator search for the spoken address, next to the dashboard. */
    private fun navigateTo(query: String) {
        say("Ищу: $query")
        val nav = Shortcuts.list.value.firstOrNull { it.pkg.startsWith("ru.yandex.yandexnavi") }
        if (nav != null) Shortcuts.launch(app, nav)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://map_search?text=" + Uri.encode(query)))
            .setPackage("ru.yandex.yandexnavi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(i) }
    }

    private fun go(t: VoiceTarget, label: String) {
        say(label)
        navigate.value = SystemClock.elapsedRealtime() to t
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i != null) runCatching { app.startActivity(i) }
    }

    private fun volume(steps: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(kotlin.math.abs(steps)) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
        say(if (steps > 0) "Громче" else "Тише")
    }

    /** The head unit's own music, radio or Bluetooth audio is what plays now. */
    private fun fytPlaying(): Boolean {
        if (!Fyt.present(app)) return false
        val androidMusic = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
        // FYT radio plays past Android's audio: it counts only while no app plays music
        return Fyt.music.value.playing || (Fyt.appId.value == 1 && !androidMusic)
    }

    /** Some other app holds the music stream (Fyt's player is silent). */
    private fun otherAppPlaying() = (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive && !Fyt.music.value.playing

    private fun mediaKey(code: Int, label: String) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        say(label)
    }

    private fun say(text: String) {
        toast.value = SystemClock.elapsedRealtime() to text
    }

    private fun beep() {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_BEEP2, 150) }
    }

    private fun phraseMatches(cmd: String, phrase: String): Boolean {
        val pw = phrase.split(" ").filter { it.isNotBlank() }
        val cw = cmd.split(" ")
        return pw.all { p -> cw.any { similar(it, p) } }
    }

    /** Fuzzy word match: tolerates the recogniser's spelling of unusual words (e.g. brand names). */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        val d = levenshtein(a, b)
        return d <= maxOf(1, b.length / 4) || (b.length >= 5 && (a.startsWith(b.take(4)) || b.startsWith(a.take(4))))
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
