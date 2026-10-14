package kg.forester.sg.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle

/**
 * Only a notification listener may see other apps' media sessions: it gives the voice shuffle, repeat, rewind,
 * likes and "what's playing" for Yandex Music, VK, Spotify… The driver allows it once in the Android settings
 * («Доступ к уведомлениям»); without it these commands fall back to the media keys or say they can't.
 */
class MediaListener : android.service.notification.NotificationListenerService()

object MediaSessions {
    fun allowed(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** The session that plays now, else the latest one; null without the listener permission. */
    fun controller(ctx: Context): MediaController? = runCatching {
        val all = ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }.getOrNull()

    // the androidx.media "compat" custom actions every MediaSessionCompat / Media3 session understands
    fun shuffle(ctx: Context, on: Boolean): Boolean = controller(ctx)?.transportControls?.sendCustomAction(
        "android.support.v4.media.session.action.SET_SHUFFLE_MODE",
        Bundle().apply { putInt("android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE", if (on) 1 else 0) },
    ) != null

    /** [mode]: 0 off, 1 this track, 2 all. */
    fun repeat(ctx: Context, mode: Int): Boolean = controller(ctx)?.transportControls?.sendCustomAction(
        "android.support.v4.media.session.action.SET_REPEAT_MODE",
        Bundle().apply { putInt("android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE", mode) },
    ) != null

    /** "Artist — title" of what plays, or null. */
    fun nowPlaying(ctx: Context): String? {
        val m = controller(ctx)?.metadata ?: return null
        val title = m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        return listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { null }
    }
}
