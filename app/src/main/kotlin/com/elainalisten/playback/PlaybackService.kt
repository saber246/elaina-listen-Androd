package com.elainalisten.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.elainalisten.ElainaApplication
import com.elainalisten.MainActivity
import com.elainalisten.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val reading get() = (application as ElainaApplication).reading
    private lateinit var session: MediaSession
    private lateinit var openPlayer: PendingIntent
    private var preparing = false

    override fun onCreate() {
        super.onCreate()
        openPlayer = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel)
            .build().apply { setSmallIcon(R.drawable.ic_headphones) })
        session = MediaSession.Builder(this, reading.mediaPlayer)
            .setSessionActivity(openPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo):
                    MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailablePlayerCommands(commands).build()
                }
            }).build()
        addSession(session)
        scope.launch {
            reading.state.collect { state ->
                if (preparing && !state.generating && !state.turningPage && !state.playRequested) {
                    preparing = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (reading.mediaPlayer.mediaItemCount == 0) stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != PREPARE_PLAYBACK) return super.onStartCommand(intent, flags, startId)
        if (!isPlaybackOngoing) {
            preparing = true
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_headphones)
                .setContentTitle(reading.state.value.article?.title ?: getString(R.string.app_name))
                .setContentText(getString(R.string.playback_preparing))
                .setContentIntent(openPlayer)
                .setOngoing(true)
                .build()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            if (!reading.state.value.playRequested) {
                preparing = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (reading.mediaPlayer.mediaItemCount == 0) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val state = reading.state.value
        if (!startInForegroundRequired && (state.turningPage || (state.generating && state.playRequested))) {
            if (session.player.mediaItemCount == 0) return
        }
        if (startInForegroundRequired) preparing = false
        super.onUpdateNotification(session, startInForegroundRequired || state.turningPage ||
            (state.generating && state.playRequested && session.player.mediaItemCount > 0))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        scope.cancel()
        removeSession(session)
        session.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        private const val PREPARE_PLAYBACK = "com.elainalisten.PREPARE_PLAYBACK"
        private const val CHANNEL_ID = "reading"
        private const val NOTIFICATION_ID = 101

        fun prepare(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)
                .setAction(PREPARE_PLAYBACK))
        }
    }
}
