package kg.forester.sg.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kg.forester.sg.ssm.SsmTrace

/**
 * The Media screen's own player for music files: a USB stick, an SD card or the unit's memory, browsed by folder.
 * One ExoPlayer for the app, wrapped in a media session by [PlayerService] (notification, steering wheel keys,
 * other apps pause it). It remembers the folder, the track and the place in it, like a car stereo.
 */
object LocalMusic {
    enum class Kind { USB, SD, MEMORY }

    data class Root(val dir: File, val name: String, val kind: Kind)

    data class Listing(val folders: List<File>, val tracks: List<File>)

    data class State(
        val folder: String = "",
        val queue: List<String> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: Bitmap? = null,
        val durMs: Long = 0,
        val shuffle: Boolean = false,
        /** Player.REPEAT_MODE_OFF / _ALL / _ONE. */
        val repeat: Int = Player.REPEAT_MODE_ALL,
        /** What comes after this one ("" at the end). */
        val nextTitle: String = "",
        /** Playback speed; offered for long files (audiobooks, podcasts) only. */
        val speed: Float = 1f,
        val audioSession: Int = 0,
        /** Internet radio: [station] plays, [title] is the song the stream announces, [artUrl] the station's logo. */
        val stream: Boolean = false,
        val station: String = "",
        val artUrl: String = "",
        /** Why it doesn't play (a station that doesn't answer, a broken file), "" when fine. */
        val error: String = "",
    ) {
        val long: Boolean get() = durMs > LONG_MS
        val current: String? get() = queue.getOrNull(index)
    }

    /** Longer than this, a file is an audiobook or a podcast: it keeps its own place and may play faster. */
    const val LONG_MS = 20 * 60_000L

    private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "mka", "alac", "amr", "3gp")

    val state = MutableStateFlow(State())
    @Volatile private var exo: ExoPlayer? = null
    private var appCtx: Context? = null

    /** A file's name for the list: without the extension and the leading track number ("03 - Song" → "Song"). */
    fun pretty(f: File): String = f.nameWithoutExtension.replace(Regex("^\\d{1,3}(\\s*[-._)]\\s*|\\s+)"), "").ifBlank { f.nameWithoutExtension }

    fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO

    /** Mounted storage: removable ones first (a USB stick, a card), then the unit's memory. */
    fun roots(ctx: Context): List<Root> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val out = ArrayList<Root>()
        runCatching {
            for (v in sm.storageVolumes) {
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = (if (Build.VERSION.SDK_INT >= 30) v.directory else
                    runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as File }.getOrNull()) ?: continue
                val desc = v.getDescription(ctx).orEmpty()
                val kind = when {
                    v.isPrimary || !v.isRemovable -> Kind.MEMORY
                    desc.contains("usb", true) || dir.path.contains("usb", true) -> Kind.USB
                    desc.contains("sd", true) -> Kind.SD
                    else -> Kind.USB
                }
                val name = when (kind) { Kind.MEMORY -> "Память"; Kind.SD -> "Карта"; Kind.USB -> "Флешка" }
                out += Root(dir, name, kind)
            }
        }
        // some head units mount a stick outside the volumes Android lists
        File("/storage").listFiles()?.filter { d ->
            d.isDirectory && d.canRead() && d.name != "emulated" && d.name != "self" && out.none { it.dir.path == d.path }
        }?.forEach { out += Root(it, "Флешка", Kind.USB) }
        if (out.none { it.kind == Kind.MEMORY }) out += Root(Environment.getExternalStorageDirectory(), "Память", Kind.MEMORY)
        // two sticks: number them
        val usb = out.filter { it.kind == Kind.USB }
        return out.sortedBy { it.kind.ordinal }.map { r ->
            if (r.kind == Kind.USB && usb.size > 1) r.copy(name = "Флешка ${usb.indexOf(r) + 1}") else r
        }
    }

    /**
     * Folders that hold music somewhere inside, from Android's media index (it scans sticks and cards too), so the
     * list skips Alarms, DCIM and the like. Empty when the index has nothing: then every folder is shown.
     */
    @Volatile private var musicDirs: Set<String> = emptySet()
    @Volatile private var musicDirsAt = 0L

    fun refreshMusicDirs(ctx: Context) {
        if (System.currentTimeMillis() - musicDirsAt < 15_000) return
        musicDirsAt = System.currentTimeMillis()
        val out = HashSet<String>()
        runCatching {
            // every volume: the unit's memory, sticks, cards
            val volumes = if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.getExternalVolumeNames(ctx) else setOf("external")
            for (v in volumes) {
                @Suppress("DEPRECATION")
                ctx.contentResolver.query(android.provider.MediaStore.Audio.Media.getContentUri(v),
                    arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        var d = c.getString(0)?.let { File(it).parentFile }
                        while (d != null && out.add(d.path)) d = d.parentFile
                    }
                }
            }
        }.onFailure { SsmTrace.log("music index: $it") }
        musicDirs = out
    }

    /** A folder's subfolders and music files, in the order people expect ("2 …" before "10 …"). */
    fun list(dir: File): Listing {
        val all = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
        val known = musicDirs
        val folders = all.filter { it.isDirectory && it.name != "Android" && it.name != "LOST.DIR" }
        // only folders with music, when the index knows this drive (a fresh stick may not be scanned yet)
        val withMusic = if (known.isEmpty() || dir.path !in known) folders else folders.filter { it.path in known }
        return Listing(withMusic.sortedWith(natural), all.filter(::isAudio).sortedWith(natural))
    }

    private val natural = Comparator<File> { a, b ->
        val x = a.name.lowercase(); val y = b.name.lowercase()
        var i = 0; var j = 0
        while (i < x.length && j < y.length) {
            if (x[i].isDigit() && y[j].isDigit()) {
                var i2 = i; while (i2 < x.length && x[i2].isDigit()) i2++
                var j2 = j; while (j2 < y.length && y[j2].isDigit()) j2++
                val n = x.substring(i, i2).toBigInteger().compareTo(y.substring(j, j2).toBigInteger())
                if (n != 0) return@Comparator n
                i = i2; j = j2
            } else {
                if (x[i] != y[j]) return@Comparator x[i].compareTo(y[j])
                i++; j++
            }
        }
        (x.length - i).compareTo(y.length - j)
    }

    /** The app's player, made on the main thread at first use; the last folder and place come back paused. */
    fun player(ctx: Context): ExoPlayer {
        exo?.let { return it }
        val app = ctx.applicationContext
        appCtx = app
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        AudioFx.load(app)
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refresh(player)
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) save()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) { AudioFx.attach(audioSessionId) }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                SsmTrace.log("player: ${error.errorCodeName} ${p.currentMediaItem?.mediaId}")
                val stream = p.currentMediaItem?.mediaId?.startsWith(STREAM) == true
                state.value = state.value.copy(error = if (stream) "Станция не отвечает, попробуйте другую" else "Этот файл не играет (${error.errorCodeName})")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) state.value = state.value.copy(error = "") }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // an audiobook chapter picks up where it was left; a finished one forgets its place
                val sp = prefs() ?: return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastId?.let { sp.edit().remove("book:$it").apply() }
                lastId = item?.mediaId
                val at = item?.mediaId?.let { sp.getLong("book:$it", 0) } ?: 0
                if (at > 0 && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) p.seekTo(at)
            }
        })
        exo = p
        AudioFx.attach(p.audioSessionId)
        restore(app, p)
        // the louder the road, the louder the music (only while it plays)
        handler.post(object : Runnable {
            override fun run() {
                if (exo !== p) return
                if (p.isPlaying) {
                    val e = kg.forester.sg.data.Repo.live.value
                    val gps = kg.forester.sg.data.Repo.gps.value
                    AudioFx.onSpeed(e.speedKmh?.toFloat() ?: gps?.takeIf { System.currentTimeMillis() - it.timeMs < 5000 }?.speedKmh)
                    if (System.currentTimeMillis() - lastSaveMs > 10_000) save()
                }
                handler.postDelayed(this, 2000)
            }
        })
        return p
    }

    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var lastId: String? = null
    private var lastSaveMs = 0L

    private fun refresh(p: Player) {
        val md = p.mediaMetadata
        val path = p.currentMediaItem?.mediaId.orEmpty()
        val old = state.value
        val art = if (path == old.current && old.art != null) old.art else artFor(md.artworkData, path)
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
        // music always at its own speed, a long file at the speed chosen for books
        val want = if (dur > LONG_MS) bookSpeed() else 1f
        if (dur > 0 && p.playbackParameters.speed != want) p.setPlaybackSpeed(want)
        val ni = p.nextMediaItemIndex
        val item = p.currentMediaItem
        if (path.startsWith(STREAM)) {
            val st = item?.mediaMetadata?.station?.toString().orEmpty()
            val song = md.title?.toString().orEmpty().takeIf { it.isNotBlank() && it != st }
            state.value = old.copy(
                queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId },
                index = p.currentMediaItemIndex, playing = p.isPlaying,
                title = song ?: st, artist = if (song != null) st else "Интернет-радио", album = "",
                art = null, durMs = 0, shuffle = false, repeat = Player.REPEAT_MODE_ALL,
                nextTitle = if (ni == C.INDEX_UNSET) "" else p.getMediaItemAt(ni).mediaMetadata.station?.toString().orEmpty(),
                speed = 1f, audioSession = (p as? ExoPlayer)?.audioSessionId ?: 0,
                stream = true, station = st, artUrl = item?.mediaMetadata?.artworkUri?.toString().orEmpty(),
            )
            return
        }
        state.value = old.copy(
            stream = false, station = "", artUrl = "",
            nextTitle = if (ni == C.INDEX_UNSET) "" else pretty(File(p.getMediaItemAt(ni).mediaId)),
            speed = want,
            audioSession = (p as? ExoPlayer)?.audioSessionId ?: 0,
            queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId },
            index = p.currentMediaItemIndex,
            playing = p.isPlaying,
            title = md.title?.toString()?.takeIf { it.isNotBlank() } ?: pretty(File(path)),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            art = art,
            durMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
        )
    }

    /** The cover in the file's tags, else a cover.jpg / folder.jpg next to it; scaled down for the screen. */
    private fun artFor(data: ByteArray?, path: String): Bitmap? {
        fun decode(load: (BitmapFactory.Options) -> Bitmap?): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            load(o)
            var s = 1
            while (o.outWidth / (s * 2) >= 480 && o.outHeight / (s * 2) >= 480) s *= 2
            load(BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
        if (data != null) decode { BitmapFactory.decodeByteArray(data, 0, data.size, it) }?.let { return it }
        val dir = File(path).parentFile ?: return null
        val img = dir.listFiles()?.firstOrNull { f ->
            f.extension.lowercase() in setOf("jpg", "jpeg", "png") &&
                f.nameWithoutExtension.lowercase() in setOf("cover", "folder", "front", "album", "albumart")
        } ?: return null
        return decode { BitmapFactory.decodeFile(img.path, it) }
    }

    const val STREAM = "stream:"

    /** Internet radio: [list] as a queue (⏮ ⏭ go through it), from [start]. */
    fun playStreams(ctx: Context, list: List<RadioLogos.Station>, start: Int) {
        if (list.isEmpty()) return
        // a station with an odd stream must not take the app down
        runCatching { startStreams(ctx, list, start) }.onFailure { SsmTrace.log("stream: $it") }
    }

    private fun startStreams(ctx: Context, list: List<RadioLogos.Station>, start: Int) {
        val p = player(ctx)
        startService(ctx)
        p.shuffleModeEnabled = false
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.setMediaItems(list.map { st ->
            MediaItem.Builder().setUri(st.url).setMediaId(STREAM + st.url)
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setStation(st.name)
                    .setArtworkUri(st.logo.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build())
                .build()
        }, start.coerceIn(list.indices), 0L)
        p.prepare()
        play(p)
        val id = list[start.coerceIn(list.indices)].id
        Thread { RadioLogos.clicked(id) }.start()
    }

    /** Plays [tracks] of [folder] from [start]; [shuffle] mixes the folder. */
    fun playFolder(ctx: Context, folder: File, tracks: List<File>, start: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val p = player(ctx)
        startService(ctx)
        state.value = state.value.copy(folder = folder.path, art = null)
        p.setMediaItems(tracks.map { MediaItem.Builder().setUri(Uri.fromFile(it)).setMediaId(it.path).build() },
            if (shuffle) tracks.indices.random() else start.coerceIn(tracks.indices), 0L)
        p.shuffleModeEnabled = shuffle
        p.prepare()
        play(p)
        remember(folder)
    }

    /** Starts playing; with «плавное начало» the music comes up over a second instead of blasting in. */
    private fun play(p: ExoPlayer) {
        if (!AudioFx.cfg.value.fadeIn) { p.volume = 1f; p.play(); return }
        p.volume = 0f
        p.play()
        val t0 = android.os.SystemClock.uptimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                val f = ((android.os.SystemClock.uptimeMillis() - t0) / 1200f).coerceIn(0f, 1f)
                p.volume = f * f
                if (f < 1f) handler.postDelayed(this, 40)
            }
        })
    }

    fun seekBy(ctx: Context, ms: Long) {
        val p = player(ctx)
        p.seekTo((p.currentPosition + ms).coerceIn(0, (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)))
    }

    fun bookSpeed(): Float = prefs()?.getFloat("bookSpeed", 1f) ?: 1f
    fun setBookSpeed(ctx: Context, v: Float) {
        prefs()?.edit()?.putFloat("bookSpeed", v)?.apply()
        player(ctx).let { if (it.duration > LONG_MS) it.setPlaybackSpeed(v) }
    }

    // --- favourite and recent folders ---------------------------------------------------------------------

    fun favourites(): List<File> = prefs()?.getStringSet("favs", emptySet()).orEmpty().map(::File).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    fun isFavourite(dir: File) = prefs()?.getStringSet("favs", emptySet())?.contains(dir.path) == true
    fun setFavourite(dir: File, on: Boolean) {
        val s = prefs()?.getStringSet("favs", emptySet()).orEmpty().toMutableSet()
        if (on) s += dir.path else s -= dir.path
        prefs()?.edit()?.putStringSet("favs", s)?.apply()
    }

    /** Folders played lately, newest first. */
    fun recent(): List<File> = prefs()?.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }.map(::File).filter { it.isDirectory }

    private fun remember(dir: File) {
        val l = (listOf(dir.path) + recent().map { it.path }).distinct().take(8)
        prefs()?.edit()?.putString("recent", l.joinToString("\n"))?.apply()
    }

    fun toggle(ctx: Context) {
        val p = player(ctx)
        if (p.mediaItemCount == 0) return
        startService(ctx)
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
            play(p)
        }
    }

    fun pause() { exo?.pause() }

    fun next(ctx: Context) = player(ctx).let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    /** Back to the start of the track after the first seconds, to the one before right at its start, like a CD. */
    fun prev(ctx: Context) = player(ctx).seekToPrevious()
    fun seek(ctx: Context, ms: Long) = player(ctx).seekTo(ms)
    fun position(): Long = exo?.currentPosition ?: 0
    fun setShuffle(ctx: Context, on: Boolean) { player(ctx).shuffleModeEnabled = on }
    fun cycleRepeat(ctx: Context) {
        val p = player(ctx)
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
    }

    private fun startService(ctx: Context) {
        runCatching { ctx.startService(Intent(ctx, PlayerService::class.java)) }
    }

    // --- remembering where it was ---------------------------------------------------------------------

    private fun prefs() = appCtx?.getSharedPreferences("music", Context.MODE_PRIVATE)

    fun save() {
        val p = exo ?: return
        val path = p.currentMediaItem?.mediaId ?: return
        if (path.startsWith(STREAM)) return // a stream has no place to come back to
        lastSaveMs = System.currentTimeMillis()
        if (p.duration > LONG_MS && p.currentPosition > 30_000) prefs()?.edit()?.putLong("book:$path", p.currentPosition)?.apply()
        prefs()?.edit()
            ?.putString("folder", state.value.folder.ifEmpty { File(path).parent.orEmpty() })
            ?.putString("track", path)
            ?.putLong("pos", p.currentPosition)
            ?.putBoolean("shuffle", p.shuffleModeEnabled)
            ?.putInt("repeat", p.repeatMode)
            ?.apply()
    }

    private fun restore(ctx: Context, p: ExoPlayer) {
        val sp = ctx.getSharedPreferences("music", Context.MODE_PRIVATE)
        p.repeatMode = sp.getInt("repeat", Player.REPEAT_MODE_ALL)
        p.shuffleModeEnabled = sp.getBoolean("shuffle", false)
        val folder = File(sp.getString("folder", null) ?: return)
        // the stick may be out: then it waits for the next play from the library
        val tracks = runCatching { list(folder).tracks }.getOrDefault(emptyList())
        if (tracks.isEmpty()) return
        val i = tracks.indexOfFirst { it.path == sp.getString("track", "") }.coerceAtLeast(0)
        state.value = state.value.copy(folder = folder.path)
        p.setMediaItems(tracks.map { MediaItem.Builder().setUri(Uri.fromFile(it)).setMediaId(it.path).build() }, i,
            if (i >= 0) sp.getLong("pos", 0) else 0)
        p.prepare()
    }

    /** When the service goes (exit, swiped away while paused). */
    fun release() {
        save()
        AudioFx.stopSpectrum()
        AudioFx.release()
        exo?.release()
        exo = null
        state.value = State()
    }
}
