package im.vector.app.push.fcm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import im.vector.app.R
import timber.log.Timber
import java.io.IOException

class AudioPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val audioUrl = intent?.getStringExtra("audio_url")
        if (audioUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val dismissIntent = Intent(this, NotificationDismissReceiver::class.java)
        val deletePendingIntent = PendingIntent.getBroadcast(
                this, 0, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID")
                .setContentTitle("Push-to-Talk")
                .setContentText("Playing audio message...")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDeleteIntent(deletePendingIntent) // 👈 This line is key
                .build()

        startForeground(1, notification)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                    AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
            )
            try {
                setDataSource(audioUrl)
            } catch (e: IOException) {
                Timber.e(e, "Failed to setDataSource($audioUrl)")
                stopSelf()
                return@apply
            }
            prepareAsync()
            setOnPreparedListener {
                Timber.d("🔊 AudioPlaybackService: media prepared, starting playback.")
                it.start()
            }
            setOnCompletionListener {
                Timber.d("🔊 AudioPlaybackService: playback completed, stopping service.")
                stopSelf()
            }
            setOnErrorListener { _, what, extra ->
                Timber.e("🔴 AudioPlaybackService: MediaPlayer error (what=$what, extra=$extra)")
                stopSelf()
                true
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Timber.tag("PTT").d("Notification dismissed by user. Stopping audio.")

        // Stop the AudioPlaybackService
        val stopIntent = Intent(context, AudioPlaybackService::class.java)
        context.stopService(stopIntent)
    }
}
