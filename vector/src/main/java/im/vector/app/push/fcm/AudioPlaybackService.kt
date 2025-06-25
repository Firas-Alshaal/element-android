package im.vector.app.push.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import im.vector.app.R
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AudioPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val audioUrl = intent?.getStringExtra("audio_url")?.replace("https://", "http://")
        if (audioUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val dismissIntent = Intent(this, NotificationDismissReceiver::class.java)
        val deletePendingIntent = PendingIntent.getBroadcast(
                this, 0, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "PTT_AUDIO_NOTIFICATION"

        createNotificationChannel(channelId)

        val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Push-to-Talk")
                .setContentText("Playing voice message")
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW) // Not high to avoid double alerts
                .setDeleteIntent(deletePendingIntent)
                .setOngoing(true)
                .build()

        startForeground(1, notification)

        Thread {
            try {
                // Optional: Test connection off main thread
                val url = URL(audioUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "GET"
                connection.connect()
                val responseCode = connection.responseCode
                Timber.d("🔍 Connection response code: $responseCode")
                connection.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "🚫 Connection test failed.")
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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

                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                    stopSelf()
                }
                setOnErrorListener { _, what, extra ->
                    Timber.e("🔴 MediaPlayer error (what=$what, extra=$extra)")
                    stopSelf()
                    true
                }
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    private fun createNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "PTT Voice Playback"
            val descriptionText = "Channel for Push-to-Talk voice messages"
            val importance = NotificationManager.IMPORTANCE_LOW // Avoid duplicate alert
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            notificationManager.createNotificationChannel(channel)
        }
    }

}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Timber.tag("PTT").d("Notification dismissed by user. Stopping audio.")

        // Stop the AudioPlaybackService
        val stopIntent = Intent(context, AudioPlaybackService::class.java)
        context.stopService(stopIntent)
    }
}

