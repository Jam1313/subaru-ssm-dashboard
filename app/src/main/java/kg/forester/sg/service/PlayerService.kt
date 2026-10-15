package kg.forester.sg.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps [LocalMusic] playing with the screen elsewhere: a media session (the notification with its controls,
 * the steering wheel and Bluetooth keys, voice «пауза» / «следующий») and the foreground service it needs.
 */
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        session = MediaSession.Builder(this, LocalMusic.player(this)).apply { open?.let { setSessionActivity(it) } }.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        LocalMusic.release()
        super.onDestroy()
    }
}
