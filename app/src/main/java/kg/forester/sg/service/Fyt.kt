package kg.forester.sg.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

/*
 * Talks to the FYT/Teyes system service com.syu.ms over its private binder protocol (com.syu.ipc), and receives
 * now-playing pushes from the stock player. See research/fyt_ipc.md for the protocol notes.
 */

data class MusicState(
    val source: String = "",           // "music" (stock player) or "bt"
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val posMs: Long = 0,
    val totalMs: Long = 0,
    val updatedMs: Long = 0,
)

data class RadioState(
    val band: Int = -1,                // 65536..65538 FM1..3, 0/1 AM
    val freq: Int = 0,                 // FM in 10 kHz units, AM in kHz
    val preset: Int = 0,
    val name: String = "",
    val logo: Bitmap? = null,
) {
    val isFm get() = band >= 65536
    val freqText: String get() = if (freq == 0) "—" else if (isFm || band == -1) String.format(Locale.US, "%.2f", freq / 100.0) else "$freq"
    val bandText: String get() = when (band) { 0 -> "AM1"; 1 -> "AM2"; 65536 -> "FM1"; 65537 -> "FM2"; 65538 -> "FM3"; else -> "FM" }
}

/** Receives `com.fyt.launcher.music` from com.syu.music (sent to the default launcher about once a second). */
class FytMusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.extras?.let { e ->
            Fyt.music.value = MusicState(
                source = "music",
                title = e.getString("title").orEmpty(),
                artist = e.getString("play_artist").orEmpty(),
                playing = e.getBoolean("play_state", false),
                posMs = e.getLong("play_cur", 0),
                totalMs = e.getLong("play_total", 0),
                updatedMs = System.currentTimeMillis(),
            )
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

object Fyt {
    /** True on FYT-platform head units (Teyes and other UIS7862/… units): the system service is installed. */
    fun present(ctx: android.content.Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo("com.syu.ms", 0); true }.getOrDefault(false)

    private const val D_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val D_MODULE = "com.syu.ipc.IRemoteModule"
    private const val D_CALLBACK = "com.syu.ipc.IModuleCallback"
    private const val M_MAIN = 0
    private const val M_RADIO = 1
    private const val M_BT = 2

    val music = MutableStateFlow(MusicState())
    val radio = MutableStateFlow(RadioState())
    val appId = MutableStateFlow(0) // current audio source: 1 radio, 3 BT music, 8 music player

    private var main: IBinder? = null
    private var radioModule: IBinder? = null
    private var bt: IBinder? = null
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stations: Map<String, Pair<String, String>> = emptyMap() // "102.90" -> (id, name)

    // --- binder plumbing -------------------------------------------------------------------------

    private abstract class Callback : Binder() {
        init { attachInterface(null, D_CALLBACK) }
        abstract fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                data.enforceInterface(D_CALLBACK)
                val c = data.readInt(); val i = data.createIntArray(); data.createFloatArray(); val s = data.createStringArray()
                runCatching { onUpdate(c, i, s) }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun module(toolkit: IBinder, code: Int): IBinder? {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(D_TOOLKIT); d.writeInt(code)
            toolkit.transact(1, d, r, 0); r.readException(); r.readStrongBinder()
        } catch (e: Exception) { null } finally { d.recycle(); r.recycle() }
    }

    private fun cmd(m: IBinder?, code: Int, ints: IntArray? = null) {
        val b = m ?: return
        val d = Parcel.obtain()
        try {
            d.writeInterfaceToken(D_MODULE); d.writeInt(code)
            d.writeIntArray(ints); d.writeFloatArray(null); d.writeStringArray(null)
            b.transact(1, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally { d.recycle() }
    }

    private fun register(m: IBinder, cb: Callback, update: Int) {
        val d = Parcel.obtain()
        try {
            d.writeInterfaceToken(D_MODULE); d.writeStrongBinder(cb); d.writeInt(update); d.writeInt(1)
            m.transact(3, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally { d.recycle() }
    }

    private val mainCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            if (code == 0) appId.value = ints?.getOrNull(0) ?: 0
        }
    }

    private val radioCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            val v = ints?.getOrNull(0) ?: return
            val r = radio.value
            radio.value = when (code) {
                0 -> r.copy(band = v)
                1 -> withStation(r.copy(freq = v))
                3 -> r.copy(preset = v)
                else -> r
            }
        }
    }

    private var btState = MusicState(source = "bt")

    private val btCb = object : Callback() {
        override fun onUpdate(code: Int, ints: IntArray?, strs: Array<String?>?) {
            val m = btState
            btState = when (code) {
                0 -> m.copy(title = strs?.getOrNull(0).orEmpty())
                1 -> m.copy(artist = strs?.getOrNull(0).orEmpty())
                2 -> m.copy(totalMs = (ints?.getOrNull(0) ?: 0).toLong())
                13 -> m.copy(playing = ints?.getOrNull(0) == 1)
                28 -> m.copy(posMs = (ints?.getOrNull(0) ?: 0).toLong())
                else -> return
            }.copy(updatedMs = System.currentTimeMillis())
            // show BT only when it actually has something and the stock player isn't the fresher source
            val stockFresh = music.value.source == "music" && System.currentTimeMillis() - music.value.updatedMs < 5000
            if (btState.title.isNotBlank() && (btState.playing || !stockFresh)) music.value = btState
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val tk = service ?: return
            main = module(tk, M_MAIN)?.also { register(it, mainCb, 0) }
            radioModule = module(tk, M_RADIO)?.also { m -> listOf(0, 1, 3).forEach { register(m, radioCb, it) } }
            bt = module(tk, M_BT)?.also { m -> listOf(0, 1, 2, 13, 28).forEach { register(m, btCb, it) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            main = null; radioModule = null; bt = null
            handler.postDelayed({ app?.let { bind(it) } }, 3000)
        }
    }

    fun bind(ctx: Context) {
        app = ctx.applicationContext
        loadStations()
        runCatching {
            app!!.bindService(Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"), conn, Context.BIND_AUTO_CREATE)
        }
    }

    // --- stations (Teyes online list in /sdcard/fmbd) -----------------------------------------------

    private fun loadStations() {
        stations = runCatching {
            File("/sdcard/fmbd/fmbd.txt").readText().split('|').mapNotNull { rec ->
                val f = rec.split(',')
                if (f.size >= 5) f[4].trim() to (f[0].trim() to f[3].trim()) else null
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun withStation(r: RadioState): RadioState {
        val key = if (r.isFm || r.band == -1) String.format(Locale.US, "%.02f", r.freq / 100.0) else r.freq.toString()
        val st = stations[key] ?: return r.copy(name = "", logo = null)
        val logo = runCatching { BitmapFactory.decodeFile("/sdcard/fmbd/${st.first}.bmp") }.getOrNull()
        return r.copy(name = st.second, logo = logo)
    }

    // --- controls ----------------------------------------------------------------------------------

    private fun startMusicService(action: String) {
        runCatching { app?.startService(Intent(action).setPackage("com.syu.music")) }
    }

    fun playPause() {
        if (music.value.source == "bt") cmd(bt, 2) else startMusicService("com.syu.music.playpause")
    }

    fun next() {
        when {
            appId.value == 1 -> cmd(radioModule, 0)
            music.value.source == "bt" -> cmd(bt, 1)
            else -> startMusicService("com.syu.music.next")
        }
    }

    fun prev() {
        when {
            appId.value == 1 -> cmd(radioModule, 1)
            music.value.source == "bt" -> cmd(bt, 0)
            else -> startMusicService("com.syu.music.prev")
        }
    }

    /** Radio needs to be the active source and its activity created once; then return to our screen. */
    fun ensureRadio() {
        if (appId.value == 1) return
        val ctx = app ?: return
        runCatching {
            ctx.startActivity(Intent().setClassName("com.syu.radio", "com.syu.radio.Launch").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        cmd(main, 0, intArrayOf(1))
        handler.postDelayed({
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); runCatching { ctx.startActivity(it) }
            }
        }, 900)
    }

    fun radioSeek(up: Boolean) { ensureRadio(); cmd(radioModule, if (up) 5 else 6) }
    fun radioPreset(next: Boolean) { ensureRadio(); cmd(radioModule, if (next) 0 else 1) }
    fun radioBand() { ensureRadio(); cmd(radioModule, 11) }
    fun openRadio() {
        runCatching { app?.startActivity(Intent().setClassName("com.syu.radio", "com.syu.radio.Launch").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun openMusic() {
        app?.packageManager?.getLaunchIntentForPackage("com.syu.music")?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { app?.startActivity(it) }
        }
    }
}
