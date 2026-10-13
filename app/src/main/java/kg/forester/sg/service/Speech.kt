package kg.forester.sg.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.flow.MutableStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The app's own Russian voice for the spoken warnings, so they do not depend on the head unit having a Russian
 * TTS: sherpa-onnx with a Piper voice, downloaded once (~21 MB) when the warnings are on. Irina (female, trained
 * on the RHVoice Irina voice, CC BY-SA 4.0 / GPL-3.0) by default; Dmitri and Denis (male, CC0) to choose from.
 */
object Speech {
    /** id → name shown in the settings. */
    val VOICES = linkedMapOf("irina" to "Ирина", "dmitri" to "Дмитрий", "denis" to "Денис")
    private val VOICE get() = "vits-piper-ru_RU-${kg.forester.sg.data.Repo.settings.ttsVoice}-medium-int8"
    private val URL_VOICE get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$VOICE.tar.bz2"
    private var loaded = ""

    /** "", "скачиваю 45 %", "готов", or what went wrong — for the settings. */
    val status = MutableStateFlow("")
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var downloading = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "tts/$VOICE")
    private fun model(ctx: Context) = dir(ctx).listFiles()?.firstOrNull { it.name.endsWith(".onnx") }

    fun installed(ctx: Context) = model(ctx) != null && File(dir(ctx), "tokens.txt").isFile

    val ready get() = tts != null

    /** Loads the voice if it is there, else downloads it first (in the background). */
    private var appCtx: Context? = null

    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if ((tts != null && loaded == VOICE) || downloading) return
        worker.execute {
            runCatching {
                if (!installed(app)) download(app)
                load(app)
                // the voice used before is not needed any more (~21 MB each)
                File(app.filesDir, "tts").listFiles()?.filter { it.name != VOICE }?.forEach { it.deleteRecursively() }
                status.value = "готов (${VOICES[kg.forester.sg.data.Repo.settings.ttsVoice]})"
            }.onFailure {
                SsmTrace.log("speech: $it")
                status.value = "не удалось: ${it.message ?: it.javaClass.simpleName}"
                downloading = false
            }
        }
    }

    /** Another voice was picked: drop the loaded one and get the new one. */
    fun switchVoice(ctx: Context) {
        val old = tts
        worker.execute { runCatching { old?.release() } }
        tts = null
        status.value = ""
        prepare(ctx)
    }

    private fun load(ctx: Context) {
        if (tts != null && loaded == VOICE) return
        val d = dir(ctx)
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model(ctx)!!.absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            ),
        )
        tts = OfflineTts(assetManager = null, config = cfg)
        loaded = VOICE
    }

    private fun download(ctx: Context) {
        downloading = true
        status.value = "скачиваю голос…"
        val tmp = File(ctx.cacheDir, "$VOICE.tar.bz2")
        var url = URL(URL_VOICE)
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
        File(root, VOICE).deleteRecursively()
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

    /** Says [text] with the built-in voice; false if it is not ready (the caller falls back). */
    fun say(text: String): Boolean {
        val t = tts ?: return false
        worker.execute {
            runCatching {
                val audio = t.generate(text = text, sid = 0, speed = 1.0f)
                play(audio.samples, audio.sampleRate)
            }.onFailure { SsmTrace.log("speech say: $it") }
        }
        return true
    }

    /** Plays as navigation guidance: the head unit lowers the music for it, as for a navigator's voice. */
    private fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        // ask the music to step back while speaking (like a navigator's voice), give it back afterwards
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
        runCatching { am?.requestAudioFocus(focus) }
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / rate + 200)
        track.release()
        runCatching { am?.abandonAudioFocusRequest(focus) }
    }
}
