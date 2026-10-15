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
    /** True from the moment the name is heard until the command is done: the screen shows the listening orb. */
    val listening = MutableStateFlow(false)
    /** Loudness of the microphone, 0..1, for the orb to move with the voice. */
    val level = MutableStateFlow(0f)
    /** What is being said right now (the recogniser's partial text), shown under the orb. */
    val partial = MutableStateFlow("")

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

    /** A command as if it was said (adb test hook, see MainActivity): no microphone or model needed. */
    fun runText(ctx: Context, text: String) {
        app = ctx.applicationContext
        main.post {
            listening.value = true
            doneListening(text)
            execute(text)
        }
    }

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
                // how loud: the orb follows the voice
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = kotlin.math.sqrt(sum / n) / 32768.0
                level.value = (kotlin.math.log10(rms.coerceAtLeast(1e-4)) / 2.5 + 1.6).toFloat().coerceIn(0f, 1f)
                if (rec.acceptWaveForm(buf, n)) handle(rec.result)
                else onPartial(rec.partialResult)
            }
        } catch (e: Exception) {
            status.value = "ошибка микрофона: ${e.message}"
        } finally {
            runCatching { ar.stop() }; ar.release(); agc?.release(); ns?.release(); rec.close()
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** The name heard in the middle of a phrase: show that it listens before the phrase is over. */
    private fun onPartial(json: String?) {
        val text = runCatching { JSONObject(json ?: "").optString("partial") }.getOrDefault("").trim().lowercase()
        if (text.isEmpty()) return
        val words = text.split(" ").filter { it.isNotBlank() }
        val at = Commands.wakeEnd(words, wakeWord(app))
        val armed = SystemClock.elapsedRealtime() < armedUntil
        if (at >= 0 || armed) {
            main.post {
                listening.value = true
                partial.value = if (at >= 0) words.drop(at).joinToString(" ") else text
                main.removeCallbacks(stopListening)
                main.postDelayed(stopListening, 8000)
            }
        }
    }

    private val stopListening = Runnable { listening.value = false; partial.value = "" }

    /** The orb stays a moment after the command, with what was understood, then goes. */
    private fun doneListening(text: String) {
        partial.value = text
        main.removeCallbacks(stopListening)
        main.postDelayed(stopListening, 1400)
    }

    private fun handle(json: String?) = main.post { handleOnMain(json) }

    private fun handleOnMain(json: String?) {
        val text = runCatching { JSONObject(json ?: "").optString("text") }.getOrDefault("").trim().lowercase()
        if (text.isEmpty()) return
        lastHeard.value = text
        android.util.Log.i("Voice", "heard: $text")
        val words = text.split(" ").filter { it.isNotBlank() }
        // the name also when the recogniser splits it in two ("форе стер") or hears a known variant
        val wakeAt = Commands.wakeEnd(words, wakeWord(app))
        val now = SystemClock.elapsedRealtime()
        val command = when {
            wakeAt >= 0 -> words.drop(wakeAt).joinToString(" ")
            now < armedUntil -> text
            else -> return
        }
        if (command.isBlank()) {
            armedUntil = now + 6000
            listening.value = true
            partial.value = ""
            main.removeCallbacks(stopListening)
            main.postDelayed(stopListening, 6000)
            beep()
            say("Слушаю…")
            return
        }
        armedUntil = 0
        listening.value = true
        doneListening(command)
        execute(command)
    }

    /** "Поехали" without a place: the next phrase (within 8 s, no name needed) is where to. */
    private var awaitingDestination = false

    private fun execute(cmd: String) {
        if (awaitingDestination) {
            awaitingDestination = false
            val c = Commands.parse(cmd)
            if (c?.intent == VoiceIntent.CANCEL) { say("Хорошо"); return }
            val dest = c?.takeIf { it.intent.name.startsWith("NAV_") && it.query != null }?.query ?: cmd
            say("Ищу: $dest")
            Nav.search(app, Nav.pkg(app, c?.app), dest, route = true)
            return
        }
        // the driver's own buttons first, when the whole command is their name ("навигатор", "открой музыку");
        // their own voice phrase may stand anywhere in the command
        shortcutFor(cmd)?.let { say(it.label); Shortcuts.launch(app, it); return }
        val c = Commands.parse(cmd) ?: run { say("Не понял: $cmd"); return }
        android.util.Log.i("Voice", "command: $c")
        run(c)
    }

    private val OPEN_WORDS = setOf("открой", "запусти", "покажи", "включи")

    private fun shortcutFor(cmd: String): Shortcut? {
        val cw = cmd.lowercase().split(" ").filter { it.isNotBlank() && it !in OPEN_WORDS }
        return Shortcuts.list.value.firstOrNull { s ->
            val lw = s.label.lowercase().split(" ").filter { it.isNotBlank() }
            (lw.size == cw.size && lw.indices.all { similar(cw[it], lw[it]) }) ||
                (s.phrase.isNotBlank() && phraseMatches(cmd.lowercase(), s.phrase.lowercase()))
        }
    }

    private fun run(c: VoiceCommand) {
        when (c.intent) {
            // --- music: the head unit's own player / radio / BT get FYT commands; anything else playing (Yandex
            // Music, Spotify, VK…) gets the standard media keys, which go to the app that plays now
            VoiceIntent.NEXT, VoiceIntent.DISLIKE -> { if (fytPlaying()) Fyt.next() else mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, ""); say("Следующий") }
            VoiceIntent.PREV -> { if (fytPlaying()) Fyt.prev() else mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ""); say("Предыдущий") }
            VoiceIntent.PAUSE -> {
                if (Fyt.music.value.playing) Fyt.playPause() else if (fytPlaying()) Fyt.mute(true) else mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "")
                say("Пауза")
            }
            VoiceIntent.PLAY -> {
                if (otherAppPlaying() || !Fyt.present(app)) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "") else if (!Fyt.music.value.playing) Fyt.playPause()
                Fyt.mute(false)
                say("Играю")
            }
            VoiceIntent.RESTART -> {
                MediaSessions.controller(app)?.transportControls?.seekTo(0) ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "")
                say("Сначала")
            }
            VoiceIntent.REWIND, VoiceIntent.FORWARD -> {
                val sign = if (c.intent == VoiceIntent.FORWARD) 1 else -1
                val ctl = MediaSessions.controller(app)
                val pos = ctl?.playbackState?.position
                if (ctl != null && pos != null) ctl.transportControls.seekTo((pos + sign * (c.number ?: 15) * 1000L).coerceAtLeast(0))
                else mediaKey(if (sign > 0) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND, "")
                say(if (sign > 0) "Вперёд" else "Назад")
            }
            VoiceIntent.SHUFFLE_ON, VoiceIntent.SHUFFLE_OFF ->
                say(if (MediaSessions.shuffle(app, c.intent == VoiceIntent.SHUFFLE_ON)) (if (c.intent == VoiceIntent.SHUFFLE_ON) "Перемешиваю" else "По порядку") else needSessions())
            VoiceIntent.REPEAT_ON, VoiceIntent.REPEAT_OFF -> {
                val mode = if (c.intent == VoiceIntent.REPEAT_OFF) 0 else if (c.target == "one") 1 else 2
                say(if (MediaSessions.repeat(app, mode)) (if (mode == 0) "Без повтора" else "Повтор") else needSessions())
            }
            VoiceIntent.LIKE -> {
                val ctl = MediaSessions.controller(app)
                if (ctl != null) { ctl.transportControls.setRating(android.media.Rating.newHeartRating(true)); say("Нравится") } else say(needSessions())
            }
            VoiceIntent.WHAT_PLAYING -> speak(
                when {
                    Fyt.appId.value == 1 && fytPlaying() -> Fyt.radio.value.let { r -> r.name.ifBlank { r.freqText + " " + r.bandText } }
                    Fyt.music.value.playing -> listOf(Fyt.music.value.artist, Fyt.music.value.title).filter { it.isNotBlank() }.joinToString(" — ")
                    else -> MediaSessions.nowPlaying(app) ?: if (MediaSessions.allowed(app)) "Ничего не играет" else needSessions()
                },
            )
            VoiceIntent.PLAY_QUERY -> playFromSearch(c)
            VoiceIntent.MEDIA_APP -> c.app?.let { openApp(it) } ?: say("Не нашёл приложение")
            VoiceIntent.SOURCE_BT -> { Fyt.source(3); say("Bluetooth") }
            VoiceIntent.SOURCE_USB -> { Fyt.source(8); Fyt.openMusic(); say("Музыка") }
            // --- volume
            VoiceIntent.VOLUME_UP -> volume(c.number ?: 2)
            VoiceIntent.VOLUME_DOWN -> volume(-(c.number ?: 2))
            VoiceIntent.VOLUME_SET -> setVolume(c.number ?: 10, c.percent)
            VoiceIntent.MUTE, VoiceIntent.UNMUTE -> muteMusic(c.intent == VoiceIntent.MUTE)
            // --- head unit radio
            VoiceIntent.RADIO -> { Fyt.ensureRadio(); go(VoiceTarget.MEDIA, "Радио") }
            VoiceIntent.RADIO_NEXT -> { Fyt.radioPreset(true); say("Следующая станция") }
            VoiceIntent.RADIO_PREV -> { Fyt.radioPreset(false); say("Предыдущая станция") }
            VoiceIntent.RADIO_SEEK -> { Fyt.radioSeek((c.number ?: 1) > 0); say("Ищу станцию") }
            VoiceIntent.RADIO_BAND -> { Fyt.radioBand(); say("Диапазон") }
            VoiceIntent.RADIO_FREQ, VoiceIntent.RADIO_PRESET, VoiceIntent.RADIO_NAME -> {
                // choosing a station directly needs a radio command code we don't have yet
                Fyt.ensureRadio(); go(VoiceTarget.MEDIA, "Радио")
                speak("Станцию по номеру пока не умею: скажите «следующая станция»")
            }
            // --- navigation
            VoiceIntent.NAV_ROUTE -> {
                val q = c.query
                if (q == null) { awaitingDestination = true; armedUntil = SystemClock.elapsedRealtime() + 8000; beep(); say("Куда едем?") }
                else {
                    say("Ищу: $q" + (c.via?.let { " (через $it — добавьте в навигаторе)" } ?: ""))
                    Nav.search(app, Nav.pkg(app, c.app), q, route = true)
                }
            }
            VoiceIntent.NAV_SEARCH, VoiceIntent.NAV_NEARBY -> {
                val q = c.query ?: c.target ?: return say("Что найти?")
                say("Ищу: $q")
                Nav.search(app, Nav.pkg(app, c.app), q, route = false)
            }
            VoiceIntent.NAV_HOME, VoiceIntent.NAV_WORK -> {
                val key = if (c.intent == VoiceIntent.NAV_HOME) "home" else "work"
                val p = prefs(app)
                val lat = p.getFloat("${key}Lat", Float.NaN); val lon = p.getFloat("${key}Lon", Float.NaN)
                val pkg = Nav.pkg(app, c.app)
                when {
                    !lat.isNaN() && !lon.isNaN() -> { say(if (key == "home") "Едем домой" else "Едем на работу"); Nav.routeTo(app, pkg, lat.toDouble(), lon.toDouble()) }
                    else -> speak("Сначала скажите «запомни это место как ${if (key == "home") "дом" else "работу"}», когда будете там")
                }
            }
            VoiceIntent.NAV_SAVE_PLACE -> {
                val g = kg.forester.sg.data.Repo.gps.value
                val key = if (c.target == "work") "work" else "home"
                if (g?.lat == null || g.lon == null) speak("Нет координат GPS")
                else {
                    prefs(app).edit().putFloat("${key}Lat", g.lat.toFloat()).putFloat("${key}Lon", g.lon.toFloat()).apply()
                    speak(if (key == "home") "Запомнил дом" else "Запомнил работу")
                }
            }
            VoiceIntent.NAV_TRAFFIC -> { Nav.traffic(app, Nav.pkg(app, c.app)); say("Пробки") }
            VoiceIntent.NAV_OPEN -> { c.app?.let { openApp(it) } ?: kg.forester.sg.ui.openNavigator(app); say("Навигатор") }
            // no navigator takes these from outside: open it and let its own assistant do it
            VoiceIntent.NAV_CANCEL, VoiceIntent.NAV_ETA, VoiceIntent.NAV_SOUND_OFF, VoiceIntent.NAV_SOUND_ON -> {
                kg.forester.sg.ui.openNavigator(app)
                speak("Скажите навигатору: «Слушай, Алиса, " + when (c.intent) {
                    VoiceIntent.NAV_CANCEL -> "отмени маршрут»"
                    VoiceIntent.NAV_ETA -> "сколько ехать»"
                    VoiceIntent.NAV_SOUND_OFF -> "выключи звук»"
                    else -> "включи звук»"
                })
            }
            VoiceIntent.NAV_WHERE -> whereAmI()
            // --- our app
            VoiceIntent.SCREEN -> runCatching { VoiceTarget.valueOf(c.target ?: "") }.getOrNull()?.let { go(it, screenName(it)) } ?: say("Не понял: ${c.text}")
            VoiceIntent.FULL_SCREEN -> {
                if (c.target == "nav") Shortcuts.list.value.firstOrNull { it.icon == "navigation" }?.let { Shortcuts.launch(app, it.copy(split = false)) }
                else Shortcuts.exitSplit(app)
                say("Весь экран")
            }
            VoiceIntent.READ_VALUE -> readValue(c.target ?: "")
            VoiceIntent.APP_OPEN -> {
                val q = c.query.orEmpty()
                val a = Shortcuts.installedApps(app).firstOrNull { similar(it.label.lowercase(), q) || it.label.lowercase().startsWith(q) }
                if (a != null) { say(a.label); openApp(a.pkg) } else say("Не нашёл приложение «$q»")
            }
            VoiceIntent.CANCEL -> say("Хорошо")
            VoiceIntent.HELP -> speak("Например: поехали домой, громче, следующая, найди заправку, какая температура")
            VoiceIntent.UNSUPPORTED -> speak("Это я пока не умею")
        }
    }

    private fun screenName(t: VoiceTarget) = when (t) {
        VoiceTarget.HOME -> "Главная"; VoiceTarget.DRIVE -> "Динамика"; VoiceTarget.FUEL -> "Расход"
        VoiceTarget.GEARBOX -> "АКПП"; VoiceTarget.BATTERY -> "Аккумулятор"; VoiceTarget.DTC -> "Ошибки"; VoiceTarget.MEDIA -> "Медиа"
    }

    private fun needSessions() = "Разрешите приложению доступ к уведомлениям: Настройки → Голос"

    /** Answers that matter (values, what plays) are spoken too, not only shown. */
    private fun speak(t: String) { say(t); Speech.say(t) }

    private fun openApp(pkg: String) {
        Shortcuts.list.value.firstOrNull { it.pkg == pkg }?.let { Shortcuts.launch(app, it); return }
        app.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            ?: say("Приложение не установлено")
    }

    private val MUSIC_APPS = listOf("ru.yandex.music", "com.uma.musicvk", "com.spotify.music", "com.google.android.apps.youtube.music")

    /** "Включи группу Кино": the standard play-from-search intent of the music app, else its search screen. */
    private fun playFromSearch(c: VoiceCommand) {
        val q = c.query.orEmpty()
        if (q.isBlank()) return run(VoiceCommand(VoiceIntent.PLAY))
        // YouTube: its search with the results, the app has no "play from search" for videos
        if (c.app == "com.google.android.youtube") {
            say("Ищу на ютубе: $q")
            val search = Intent(Intent.ACTION_SEARCH).setPackage(c.app).putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(search) }.isSuccess) return
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(Intent(web).setPackage(c.app)) }.isSuccess || runCatching { app.startActivity(web) }.isSuccess) return
            say("Не нашёл ютуб на магнитоле")
            return
        }
        val pkg = c.app ?: Shortcuts.list.value.firstOrNull { it.pkg in MUSIC_APPS }?.pkg ?: MUSIC_APPS.firstOrNull { Shortcuts.isInstalled(app, it) }
        val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(android.app.SearchManager.QUERY, q)
        val ms = android.provider.MediaStore.Audio::class
        when (c.focus) {
            MediaFocus.ARTIST -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE).putExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST, q)
            MediaFocus.ALBUM -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE).putExtra(android.provider.MediaStore.EXTRA_MEDIA_ALBUM, q)
            MediaFocus.TRACK -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio").putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, q)
            MediaFocus.GENRE -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE).putExtra("android.intent.extra.genre", q)
            MediaFocus.PLAYLIST, MediaFocus.FAVORITES -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE).putExtra("android.intent.extra.playlist", q)
            else -> i.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (pkg != null) i.setPackage(pkg)
        say("Ищу: $q")
        if (i.resolveActivity(app.packageManager) != null && runCatching { app.startActivity(i) }.isSuccess) return
        val ctl = MediaSessions.controller(app)?.takeIf { pkg == null || it.packageName == pkg }
        if (ctl != null) { ctl.transportControls.playFromSearch(q, android.os.Bundle()); return }
        val web = when (pkg) {
            "com.spotify.music" -> "spotify:search:" + Uri.encode(q)
            "ru.yandex.music" -> "https://music.yandex.ru/search?text=" + Uri.encode(q)
            else -> null
        }
        if (web != null) runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        else say("Не нашёл, чем включить «$q»")
    }

    /** Volume to [n] steps (or [percent] of the maximum): stepping the MCU on FYT units, Android's volume elsewhere. */
    private fun setVolume(n: Int, percent: Boolean) {
        val cur = Fyt.volume.value
        if (Fyt.present(app) && cur >= 0) {
            val target = if (percent) Math.round(40 * n / 100f) else n // FYT volume goes 0…40
            Fyt.volumeStep(target - cur)
            say("Громкость $target")
            return
        }
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (if (percent) Math.round(max * n / 100f) else n).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        say("Громкость $v")
    }

    private fun muteMusic(on: Boolean) {
        say(if (on) "Без звука" else "Звук включён")
        if (Fyt.mute(on)) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
    }

    /** "Какая температура", "сколько бензина"…: the value spoken. */
    private fun readValue(t: String) {
        val e = kg.forester.sg.data.Repo.live.value
        val trip = kg.forester.sg.data.Repo.trip.value
        val ru = java.util.Locale("ru")
        fun n(x: Number?, unit: String) = x?.let { "${Math.round(it.toDouble())} $unit".trim() } ?: "нет данных"
        speak(when (t) {
            "COOLANT" -> "Двигатель ${n(e.coolantC, "градусов")}"
            "INTAKE", "OUTSIDE" -> "Воздух на впуске ${n(e.intakeC, "градусов")}"
            "ATF" -> "Коробка ${n(kg.forester.sg.data.Repo.tcu.value?.atfC, "градусов")}"
            "BATTERY" -> "Напряжение ${e.batteryV?.let { String.format(ru, "%.1f", it) } ?: "нет данных"} вольт"
            "SPEED" -> "Скорость ${n(e.speedKmh, "километров в час")}"
            "RPM" -> "Обороты ${n(e.rpm, "")}"
            "BOOST" -> "Наддув ${e.boostBar?.let { String.format(ru, "%.2f", it) } ?: "нет данных"} бар"
            "FUEL" -> trip.fuelLeftL?.let { "В баке ${Math.round(it)} литров" } ?: e.fuelLevelPct?.let { "В баке ${Math.round(it)} процентов" } ?: "Нет данных о топливе: отметьте заправку"
            "RANGE" -> "Хватит на ${n(trip.rangeKm, "километров")}"
            "CONSUMPTION" -> "Расход ${trip.trip?.per100?.let { String.format(ru, "%.1f", it) } ?: "нет данных"} литров на сотню"
            "TRIP" -> "Проехали ${n(trip.trip?.distanceKm, "километров")}"
            "TIME" -> java.text.SimpleDateFormat("H:mm", ru).format(java.util.Date())
            "VOLUME" -> "Громкость ${Fyt.volume.value.takeIf { it >= 0 } ?: (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC)}"
            "DTC" -> "Открываю ошибки".also { go(VoiceTarget.DTC, "Ошибки") }
            else -> "Не знаю"
        })
    }

    /** Street and city by GPS (needs the network for the address), else the point in the navigator. */
    private fun whereAmI() {
        val g = kg.forester.sg.data.Repo.gps.value
        if (g?.lat == null || g.lon == null) return speak("Нет координат GPS")
        scope.launch {
            val a = runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(app, java.util.Locale("ru")).getFromLocation(g.lat, g.lon, 1)?.firstOrNull()
            }.getOrNull()
            val text = a?.let { listOfNotNull(it.thoroughfare, it.subThoroughfare, it.locality).joinToString(", ") }?.ifBlank { null }
            main.post { if (text != null) speak(text) else Nav.routeTo(app, Nav.pkg(app, null), g.lat, g.lon) }
        }
    }

    private fun go(t: VoiceTarget, label: String) {
        say(label)
        navigate.value = SystemClock.elapsedRealtime() to t
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i != null) runCatching { app.startActivity(i) }
    }

    /**
     * On FYT units (Teyes, many FLY and others) the volume is the MCU's, like the knob: Android's stream volume
     * changes nothing there. Elsewhere the music stream, and if that didn't move, the stream the system picks.
     */
    private fun volume(steps: Int) {
        say(if (steps > 0) "Громче" else "Тише")
        if (Fyt.volumeStep(steps)) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        repeat(kotlin.math.abs(steps)) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == before) {
            repeat(kotlin.math.abs(steps)) { am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI) }
        }
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
