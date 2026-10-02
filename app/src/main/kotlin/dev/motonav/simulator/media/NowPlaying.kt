package dev.motonav.simulator.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArraySet

data class Track(val title: String, val artist: String?)

/**
 * The song currently playing in any music app, for the display's idle and live-route screens. Android only
 * exposes other apps' media sessions to an enabled notification listener, so this needs the
 * rider to grant MotoNav notification access; notifications themselves are never read.
 */
object NowPlaying {
    private const val PREFERENCES = "now_playing"
    private const val KEY_ENABLED = "enabled"

    fun interface Listener {
        fun onTrack(track: Track?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var appContext: Context? = null
    private val controllers = mutableListOf<MediaController>()

    @Volatile
    var current: Track? = null
        private set

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        recompute()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onTrack(current)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        watch(sessions.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
        override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
        override fun onSessionDestroyed() = recompute()
    }

    /** Called by [MediaNotificationListener] once Android has bound it (access granted). */
    internal fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        val manager = app.getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = manager
        val component = ComponentName(app, MediaNotificationListener::class.java)
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, mainHandler)
            watch(manager.getActiveSessions(component))
        }
    }

    internal fun detach() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        sessionManager = null
        watch(emptyList())
    }

    private fun watch(sessions: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers.clear()
        sessions.forEach { controller ->
            controller.registerCallback(controllerCallback, mainHandler)
            controllers += controller
        }
        recompute()
    }

    private fun recompute() {
        val context = appContext
        val enabled = context != null && isEnabled(context)
        // Only a session that is actually playing counts; paused music disappears from the display.
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val metadata = playing?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val track = if (enabled && !title.isNullOrBlank()) Track(title.trim(), artist?.trim()?.takeIf { it.isNotEmpty() }) else null
        if (track == current) return
        current = track
        listeners.forEach { it.onTrack(track) }
    }
}

/** Exists only so Android grants media-session access; incoming notifications are ignored. */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = NowPlaying.attach(this)
    override fun onListenerDisconnected() = NowPlaying.detach()
}
