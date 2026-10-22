package kg.forester.sg.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's own Russian voice for the spoken warnings, so they do not depend on the head unit having a Russian
 * TTS: sherpa-onnx with a voice downloaded once when the warnings are on (never shipped in the APK). Piper voices:
 * Irina (female, trained on the RHVoice Irina voice, CC BY-SA 4.0 / GPL-3.0) by default, Dmitri and Denis (male,
 * CC0), Ruslan (male, the RUSLAN corpus, CC BY-NC-SA 4.0), each as int8 (~20 MB) or full precision (~64 MB: no
 * quantisation hiss, and on ARM even faster than int8). Supertonic 3 (OpenRAIL-M, ~123 MB): a newer, more natural
 * model with five female and five male voices, as heavy for the CPU as the int8 Piper ones. Or the head unit's own
 * Android TTS (Google, RHVoice…), if it has a Russian voice. Every text goes through [SpeechText] first.
 */
object Speech {
    enum class Engine { PIPER, SUPERTONIC, SYSTEM }

    /**
     * A voice to choose: [pack] is the sherpa-onnx tts-models archive it comes in (the Supertonic voices share one,
     * a speaker [sid] each), [mb] the download.
     */
    class TtsVoice(val id: String, val name: String, val engine: Engine, val pack: String = "", val mb: Int = 0, val sid: Int = 0)

    private const val SUPERTONIC = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private val SUPERTONIC_FILES = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")

    private fun piper(id: String, name: String, hq: Boolean) =
        TtsVoice(if (hq) "$id-hq" else id, name, Engine.PIPER, "vits-piper-ru_RU-$id-medium" + if (hq) "" else "-int8", if (hq) 64 else 20)

    /** Piper voices by id (the int8 one; "-hq" after it is the full-precision one) → name. */
    val PIPER = linkedMapOf("irina" to "Ирина", "dmitri" to "Дмитрий", "denis" to "Денис", "ruslan" to "Руслан")

    val ALL: List<TtsVoice> = PIPER.flatMap { (id, name) -> listOf(piper(id, name, false), piper(id, name, true)) } +
        // voice.bin holds the style files sorted by name: F1–F5, then M1–M5
        (1..5).map { TtsVoice("st-f$it", "женский $it", Engine.SUPERTONIC, SUPERTONIC, 123, it - 1) } +
        (1..5).map { TtsVoice("st-m$it", "мужской $it", Engine.SUPERTONIC, SUPERTONIC, 123, it + 4) } +
        TtsVoice("system", "голос Android", Engine.SYSTEM)

    fun voice(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.first()
    val current get() = voice(kg.forester.sg.data.Repo.settings.ttsVoice)

    /** "Ирина", "Ирина, высокое качество", "Supertonic, женский 2", "голос Android". */
    fun title(v: TtsVoice) = when {
        v.engine == Engine.SUPERTONIC -> "Supertonic, ${v.name}"
        v.engine == Engine.PIPER && v.mb > 20 -> "${v.name}, высокое качество"
        else -> v.name
    }

    private fun url(pack: String) = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$pack.tar.bz2"
    private var loaded = ""

    /** "", "скачиваю 45 %", "готов", or what went wrong — for the settings. */
    val status = MutableStateFlow("")
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var downloading = false
    @Volatile private var system: TextToSpeech? = null
    @Volatile private var systemReady = false
    private var said = 0

    private fun dir(ctx: Context, v: TtsVoice) = File(ctx.filesDir, "tts/${v.pack}")
    private fun model(ctx: Context, v: TtsVoice) = dir(ctx, v).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    fun installed(ctx: Context, v: TtsVoice = current) = when (v.engine) {
        Engine.PIPER -> model(ctx, v) != null && File(dir(ctx, v), "tokens.txt").isFile
        Engine.SUPERTONIC -> SUPERTONIC_FILES.all { File(dir(ctx, v), it).isFile }
        Engine.SYSTEM -> true
    }

    val ready get() = if (current.engine == Engine.SYSTEM) systemReady else tts != null

    /** Loads the voice if it is there, else downloads it first (in the background). */
    private var appCtx: Context? = null

    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val v = current
        if (v.engine == Engine.SYSTEM) { prepareSystem(app); return }
        if ((tts != null && loaded == v.pack) || downloading) return
        worker.execute {
            val w = current
            if (w.engine == Engine.SYSTEM) return@execute
            runCatching {
                if (!installed(app, w)) download(app, w)
                load(app, w)
                // the voice used before is not needed any more (20–123 MB each)
                File(app.filesDir, "tts").listFiles()?.filter { it.name != w.pack }?.forEach { it.deleteRecursively() }
                if (current.pack == w.pack) status.value = "готов (${title(current)})"
            }.onFailure {
                SsmTrace.log("speech: $it")
                status.value = "не удалось: ${it.message ?: it.javaClass.simpleName}"
                downloading = false
            }
            // picked another voice while this one was downloading: the Android one needs nothing loaded, another
            // downloaded voice comes next
            if (current.engine == Engine.SYSTEM) { runCatching { tts?.release() }; tts = null; loaded = "" }
            else if (current.pack != w.pack) prepare(app)
        }
    }

    /** Another voice was picked: drop the loaded one and get the new one (a Supertonic speaker is just another sid). */
    fun switchVoice(ctx: Context) {
        val v = current
        if (v.engine != Engine.SYSTEM) system?.let { s -> system = null; systemReady = false; runCatching { s.shutdown() } }
        if (v.engine != Engine.SYSTEM && tts != null && loaded == v.pack) { status.value = "готов (${title(v)})"; return }
        val old = tts
        worker.execute { runCatching { old?.release() } }
        tts = null
        loaded = ""
        status.value = ""
        prepare(ctx)
    }

    /** The head unit's own TTS engine, with whatever Russian voice it has. */
    private fun prepareSystem(app: Context) {
        if (system != null) {
            if (systemReady) status.value = "готов (${title(current)})"
            return
        }
        status.value = "подключаю голос Android…"
        system = TextToSpeech(app) { st ->
            val s = system ?: return@TextToSpeech
            val lang = if (st == TextToSpeech.SUCCESS) runCatching { s.setLanguage(Locale("ru", "RU")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                else TextToSpeech.LANG_NOT_SUPPORTED
            systemReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
            runCatching {
                s.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
            val engine = runCatching { s.engines.firstOrNull { it.name == s.defaultEngine }?.label }.getOrNull()
            status.value = when {
                st != TextToSpeech.SUCCESS -> "на магнитоле нет синтеза речи Android"
                !systemReady -> "в голосе Android нет русского: установите Google TTS или RHVoice"
                else -> "готов (голос Android" + (engine?.let { ": $it" } ?: "") + ")"
            }
        }
    }

    private fun load(ctx: Context, v: TtsVoice) {
        if (tts != null && loaded == v.pack) return
        val d = dir(ctx, v)
        val model = when (v.engine) {
            Engine.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(d, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(d, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(d, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(d, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(d, "tts.json").absolutePath,
                    unicodeIndexer = File(d, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(d, "voice.bin").absolutePath,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            )
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model(ctx, v)!!.absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            )
        }
        // one voice that finished downloading after another was picked may still be loaded
        tts?.let { old -> tts = null; runCatching { old.release() } }
        tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = model))
        loaded = v.pack
    }

    private fun download(ctx: Context, v: TtsVoice) {
        downloading = true
        status.value = "скачиваю голос…"
        val tmp = File(ctx.cacheDir, "${v.pack}.tar.bz2")
        var url = URL(url(v.pack))
        var c: HttpURLConnection
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) error("HTTP $code")
            break
        }
        val total = c.contentLengthLong
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) status.value = "скачиваю голос: ${done * 100 / total} %"
                }
            }
        }
        status.value = "распаковываю голос…"
        val root = File(ctx.filesDir, "tts").apply { mkdirs() }
        File(root, v.pack).deleteRecursively()
        TarArchiveInputStream(BZip2CompressorInputStream(tmp.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val f = File(root, e.name).canonicalFile
                if (!f.path.startsWith(root.canonicalPath + File.separator)) continue // no "../" escapes
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { tar.copyTo(it) } }
            }
        }
        tmp.delete()
        downloading = false
    }

    /** Says [text] with the chosen voice; false if it is not ready (the caller falls back). */
    fun say(text: String): Boolean {
        val v = current
        val spoken = SpeechText.prepare(text)
        if (spoken.isBlank()) return true
        if (v.engine == Engine.SYSTEM) {
            val s = system?.takeIf { systemReady } ?: return false
            val loud = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            return runCatching { s.speak(spoken, TextToSpeech.QUEUE_ADD, loud, "speech${said++}") == TextToSpeech.SUCCESS }.getOrDefault(false)
        }
        val t = tts?.takeIf { loaded == v.pack } ?: return false
        worker.execute {
            runCatching {
                val audio = if (v.engine == Engine.SUPERTONIC)
                    t.generateWithConfig(spoken, GenerationConfig(sid = v.sid, speed = 1.05f, numSteps = 5, extra = mapOf("lang" to "ru")))
                else t.generate(text = spoken, sid = 0, speed = 1.0f)
                play(audio.samples, audio.sampleRate)
            }.onFailure { SsmTrace.log("speech say: $it") }
        }
        return true
    }

    /**
     * The voice brought to full scale (the model speaks well below it), then for «громче» and «максимум»
     * compressed, so the quiet syllables come up too and nothing clips.
     */
    private fun loud(samples: FloatArray, level: Int): ShortArray {
        val peak = samples.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.05f)
        val k = when (level) { 0 -> 0f; 1 -> 1.8f; else -> 3f }
        val norm = if (k > 0f) kotlin.math.tanh(k) else 1f
        return ShortArray(samples.size) {
            val x = samples[it] / peak
            val y = if (k > 0f) kotlin.math.tanh(k * x) / norm else x
            (y.coerceIn(-1f, 1f) * 0.97f * 32767).toInt().toShort()
        }
    }

    /**
     * Plays as navigation guidance: the head unit lowers the music for it, as for a navigator's voice; at the
     * loudest setting the music pauses while it speaks.
     */
    private fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        val level = kg.forester.sg.data.Repo.settings.voiceLoud
        val pcm = loud(samples, level)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        // ask the music to step back while speaking (like a navigator's voice), give it back afterwards. Never a
        // pause for FYT Bluetooth music: it doesn't come back after one, the phone stays paused
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val pause = level >= 2 && Fyt.appId.value != 3
        val gain = if (pause) android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        // Android 7.1 has no focus requests yet, only the old call with a listener
        val legacy = android.media.AudioManager.OnAudioFocusChangeListener { }
        val focus: Any? = if (android.os.Build.VERSION.SDK_INT >= 26) android.media.AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build() else null
        @Suppress("DEPRECATION")
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) am?.requestAudioFocus(focus as android.media.AudioFocusRequest)
            else am?.requestAudioFocus(legacy, android.media.AudioManager.STREAM_MUSIC, gain)
        }
        // the focus goes back whatever happens: kept, the music would stay paused or quiet until the app is closed
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / rate + 200)
        } finally {
            track.release()
            @Suppress("DEPRECATION")
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 26) am?.abandonAudioFocusRequest(focus as android.media.AudioFocusRequest)
                else am?.abandonAudioFocus(legacy)
            }
        }
    }
}
